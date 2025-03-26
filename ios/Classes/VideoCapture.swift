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
  // Storage property to retain the photo capture delegate
  private var currentCaptureDelegate: CapturePhotoDelegate?

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
  
  public func stopRecording(completion: @escaping (Bool) -> Void) {
    if isRecording {
      movieFileOutput.stopRecording()
      isRecording = false
      completion(true)
    } else {
      completion(false)
    }
  }

  func captureCurrentFrame(completion: @escaping (String?) -> Void) {
    // Create a photo settings object
    let settings = AVCapturePhotoSettings()
    
    // Create and retain the delegate
    let delegate = CapturePhotoDelegate(completion: { [weak self] image in
      guard let self = self, let image = image else {
        completion(nil)
        return
      }
      
      // Resize image to match preview layer dimensions
      var resizedImage = image
      if let previewLayer = self.previewLayer {
        let previewSize = previewLayer.bounds.size
        
        // Create a graphics context with the target size
        UIGraphicsBeginImageContextWithOptions(previewSize, false, 0.0)
        
        // Calculate the scaled size that preserves the aspect ratio
        let aspectRatio = image.size.width / image.size.height
        let scaledHeight = previewSize.width / aspectRatio
        let scaledWidth = previewSize.height * aspectRatio
        
        let renderSize: CGSize
        if scaledHeight > previewSize.height {
          // Width is the constraining dimension
          renderSize = CGSize(width: previewSize.width, height: scaledHeight)
        } else {
          // Height is the constraining dimension
          renderSize = CGSize(width: scaledWidth, height: previewSize.height)
        }
        
        // Calculate the center offset
        let xOffset = (previewSize.width - renderSize.width) / 2
        let yOffset = (previewSize.height - renderSize.height) / 2
        
        // Draw the image in the center
        image.draw(in: CGRect(x: xOffset, y: yOffset, width: renderSize.width, height: renderSize.height))
        
        if let resized = UIGraphicsGetImageFromCurrentImageContext() {
          resizedImage = resized
        }
        UIGraphicsEndImageContext()
      }
      
      // Create a temporary file path
      let fileName = "capture_\(Int(Date().timeIntervalSince1970)).jpg"
      let filePath = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
      
      // Convert the image to JPEG data
      guard let imageData = resizedImage.jpegData(compressionQuality: 1.0) else {
        completion(nil)
        return
      }
      
      do {
        // Write the image data to the file
        try imageData.write(to: filePath)
        print("Frame captured and saved to: \(filePath.path)")
        completion(filePath.path)
      } catch {
        print("Failed to save captured frame: \(error)")
        completion(nil)
      }
      
      // Release the delegate after completion
      self.currentCaptureDelegate = nil
    })
    
    // Store the delegate to keep it alive during the capture process
    self.currentCaptureDelegate = delegate
    
    // Start the photo capture
    photoOutput.capturePhoto(with: settings, delegate: delegate)
  }

  func shutdown() {
    // ... existing code ...
  }
}

// Custom delegate to handle the photo capture
class CapturePhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate {
  private let completion: (UIImage?) -> Void
  
  init(completion: @escaping (UIImage?) -> Void) {
    self.completion = completion
    super.init()
  }
  
  func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
    if let error = error {
      print("Error capturing photo: \(error)")
      completion(nil)
      return
    }
    
    guard let imageData = photo.fileDataRepresentation(),
          let image = UIImage(data: imageData) else {
      print("Error converting photo data to UIImage")
      completion(nil)
      return
    }
    
    completion(image)
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
