import AVFoundation
import CoreVideo
import UIKit

public protocol VideoCaptureDelegate: AnyObject {
  func videoCapture(_ capture: VideoCapture, didCaptureVideoFrame: CMSampleBuffer)
}

func bestCaptureDevice(position: AVCaptureDevice.Position) -> AVCaptureDevice {

  if UserDefaults.standard.bool(forKey: "use_telephoto"),
    let device = AVCaptureDevice.default(.builtInTelephotoCamera, for: .video, position: position)
  {
    return device
  } else if let device = AVCaptureDevice.default(
    .builtInDualCamera, for: .video, position: position)
  {
    return device
  } else if let device = AVCaptureDevice.default(
    .builtInWideAngleCamera, for: .video, position: position)
  {
    return device
  } else {
    fatalError("Missing expected back camera device.")
  }
}

public class VideoCapture: NSObject {
  public var previewLayer: AVCaptureVideoPreviewLayer?
  public weak var delegate: VideoCaptureDelegate?
  public let captureSession = AVCaptureSession()
  let videoOutput = AVCaptureVideoDataOutput()
  let photoOutput = AVCapturePhotoOutput()
  let movieFileOutput = AVCaptureMovieFileOutput()
  let cameraQueue = DispatchQueue(label: "camera-queue")
  public var lastCapturedPhoto: UIImage?
  public weak var nativeView: FLNativeView?
  private var isRecording = false

  public override init() {
    super.init()
    print("DEBUG: VideoCapture initialized")
  }

  public func setUp(
    sessionPreset: AVCaptureSession.Preset,
    position: AVCaptureDevice.Position,
    completion: @escaping (Bool) -> Void
  ) {
    print("DEBUG: Setting up video capture with position:", position)

    cameraQueue.async { [weak self] in
      guard let self = self else {
        DispatchQueue.main.async { completion(false) }
        return
      }

      // Ensure session is not running
      if self.captureSession.isRunning {
        self.captureSession.stopRunning()
      }

      self.captureSession.beginConfiguration()

      // Remove existing inputs/outputs
      for input in self.captureSession.inputs {
        self.captureSession.removeInput(input)
      }
      for output in self.captureSession.outputs {
        self.captureSession.removeOutput(output)
      }

      self.captureSession.sessionPreset = sessionPreset

      do {
        guard
          let device = AVCaptureDevice.default(
            .builtInWideAngleCamera, for: .video, position: position)
        else {
          print("DEBUG: Failed to get camera device")
          self.captureSession.commitConfiguration()
          DispatchQueue.main.async { completion(false) }
          return
        }

        let input = try AVCaptureDeviceInput(device: device)
        if self.captureSession.canAddInput(input) {
          self.captureSession.addInput(input)
          print("DEBUG: Added camera input")
        }

        // Set up video output
        self.videoOutput.videoSettings = [
          kCVPixelBufferPixelFormatTypeKey as String: NSNumber(value: kCVPixelFormatType_32BGRA)
        ]
        self.videoOutput.alwaysDiscardsLateVideoFrames = true
        self.videoOutput.setSampleBufferDelegate(self, queue: self.cameraQueue)

        if self.captureSession.canAddOutput(self.videoOutput) {
          self.captureSession.addOutput(self.videoOutput)
          print("DEBUG: Added video output")
        }

        if self.captureSession.canAddOutput(self.photoOutput) {
          self.captureSession.addOutput(self.photoOutput)
          print("DEBUG: Added photo output")
        }
        
        // Add movie file output for recording
        if self.captureSession.canAddOutput(self.movieFileOutput) {
          self.captureSession.addOutput(self.movieFileOutput)
          print("DEBUG: Added movie file output")
        }

        let connection = self.videoOutput.connection(with: .video)
        connection?.videoOrientation = .portrait
        connection?.isVideoMirrored = position == .front

        self.captureSession.commitConfiguration()

        // Set up preview layer on main thread
        DispatchQueue.main.async {
          self.previewLayer = AVCaptureVideoPreviewLayer(session: self.captureSession)
          self.previewLayer?.videoGravity = .resizeAspectFill

          if let connection = self.previewLayer?.connection, connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = position == .front
          }

          completion(true)
        }
      } catch {
        print("DEBUG: Camera setup error:", error)
        self.captureSession.commitConfiguration()
        DispatchQueue.main.async { completion(false) }
      }
    }
  }

  public func start() {
    if !captureSession.isRunning {
      cameraQueue.async {
        self.captureSession.startRunning()
        print("DEBUG: Camera started running")
      }
    }
  }

  public func stop() {
    if captureSession.isRunning {
      captureSession.stopRunning()
      // Wait for the session to stop
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
        print("DEBUG: Camera stopped running")
      }
    }
  }
  
  // MARK: - Recording Methods
  
  public func startRecording() -> String {
    if isRecording {
      return "Error: Already recording"
    }
    
    if !captureSession.isRunning {
      return "Error: Camera not running"
    }
    
    // Create a unique file path in the temporary directory
    let dateFormatter = DateFormatter()
    dateFormatter.dateFormat = "yyyyMMdd-HHmmss"
    let timestamp = dateFormatter.string(from: Date())
    let tempDirectory = NSTemporaryDirectory()
    let filePath = URL(fileURLWithPath: tempDirectory).appendingPathComponent("yolo_recording_\(timestamp).mp4")

    print("DEBUG: Starting recording to \(filePath.path)")
    
    // Start recording
    movieFileOutput.startRecording(to: filePath, recordingDelegate: self)
    isRecording = true
    
    return "Success"
  }
  
  public func stopRecording() -> String {
    if !isRecording {
      return "Error: Not recording"
    }
    
    print("DEBUG: Stopping recording")
    movieFileOutput.stopRecording()
    // isRecording will be set to false in the fileOutput delegate method
    
    return "Success"
  }

  // Function to take a photo and save it to disk
  public func takePhoto(completion: @escaping (String?, Error?) -> Void) {
    guard captureSession.isRunning else {
      completion(nil, NSError(domain: "VideoCapture", code: 1, userInfo: [NSLocalizedDescriptionKey: "Camera is not running"]))
      return
    }
    
    let settings = AVCapturePhotoSettings()
    
    // Create a unique filename with timestamp
    let dateFormatter = DateFormatter()
    dateFormatter.dateFormat = "yyyyMMdd_HHmmss"
    let timestamp = dateFormatter.string(from: Date())
    let filename = "YOLO_Photo_\(timestamp).jpg"
    
    // Get documents directory path
    guard let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
      completion(nil, NSError(domain: "VideoCapture", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not access documents directory"]))
      return
    }
    let filePath = documentsDirectory.appendingPathComponent(filename)
    
    photoOutput.capturePhoto(with: settings, delegate: PhotoCaptureProcessor(
      completionHandler: { image, error in
        guard let image = image, error == nil else {
          completion(nil, error ?? NSError(domain: "VideoCapture", code: 3, userInfo: [NSLocalizedDescriptionKey: "Failed to capture photo"]))
          return
        }
        
        // Save image to disk
        if let data = image.jpegData(compressionQuality: 0.9) {
          do {
            try data.write(to: filePath)
            completion(filePath.path, nil)
          } catch {
            completion(nil, error)
          }
        } else {
          completion(nil, NSError(domain: "VideoCapture", code: 4, userInfo: [NSLocalizedDescriptionKey: "Failed to convert image to JPEG"]))
        }
      }
    ))
  }
}

extension VideoCapture: AVCaptureVideoDataOutputSampleBufferDelegate {
  public func captureOutput(
    _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    delegate?.videoCapture(self, didCaptureVideoFrame: sampleBuffer)
  }
}

extension VideoCapture: AVCapturePhotoCaptureDelegate {
  public func photoOutput(
    _ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?
  ) {
    guard let imageData = photo.fileDataRepresentation(),
      let image = UIImage(data: imageData)
    else {
      print("DEBUG: Error converting photo to image")
      return
    }

    self.lastCapturedPhoto = image
    print("DEBUG: Photo captured successfully")
  }
}

extension VideoCapture: AVCaptureFileOutputRecordingDelegate {
  public func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {
    print("DEBUG: Recording started to \(fileURL.path)")
  }
  
  public func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
    isRecording = false
    
    if let error = error {
      print("DEBUG: Recording error: \(error.localizedDescription)")
      return
    }
    
    print("DEBUG: Recording finished successfully to \(outputFileURL.path)")
    
    // If you want to save to the photo library, you can add that functionality here
    // UISaveVideoAtPathToSavedPhotosAlbum(outputFileURL.path, self, #selector(video(_:didFinishSavingWithError:contextInfo:)), nil)
  }
  
  // Optional callback for saving to the photo library
  @objc func video(_ videoPath: String, didFinishSavingWithError error: Error?, contextInfo: UnsafeRawPointer) {
    if let error = error {
      print("DEBUG: Error saving video to photo library: \(error.localizedDescription)")
    } else {
      print("DEBUG: Video saved to photo library successfully")
    }
  }
}

// Helper class to process photo capture
class PhotoCaptureProcessor: NSObject, AVCapturePhotoCaptureDelegate {
  private let completionHandler: (UIImage?, Error?) -> Void
  
  init(completionHandler: @escaping (UIImage?, Error?) -> Void) {
    self.completionHandler = completionHandler
  }
  
  func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
    if let error = error {
      completionHandler(nil, error)
      return
    }
    
    guard let imageData = photo.fileDataRepresentation() else {
      completionHandler(nil, NSError(domain: "PhotoCapture", code: 1, userInfo: [NSLocalizedDescriptionKey: "Could not get image data"]))
      return
    }
    
    let image = UIImage(data: imageData)
    completionHandler(image, nil)
  }
}
