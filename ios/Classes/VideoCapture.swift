import AVFoundation
import CoreVideo
import UIKit
import Photos

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

public class VideoCapture: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCapturePhotoCaptureDelegate, AVCaptureFileOutputRecordingDelegate {
  public var previewLayer: AVCaptureVideoPreviewLayer?
  public weak var delegate: VideoCaptureDelegate?
  public let captureSession = AVCaptureSession()
  let videoOutput = AVCaptureVideoDataOutput()
  let photoOutput = AVCapturePhotoOutput()
  let movieFileOutput = AVCaptureMovieFileOutput()
  let cameraQueue = DispatchQueue(label: "camera-queue")
  public var lastCapturedPhoto: UIImage?
  public weak var nativeView: FLNativeView?
  
  // For picture capture
  internal var bytesPhotoCaptureDelegate: VideoCapture.BytesPhotoCaptureDelegate?
  // For video capture
  internal var isRecording = false
  internal var isCapturingFrames = false
  internal var videoWriter: AVAssetWriter?
  internal var videoWriterInput: AVAssetWriterInput?
  internal var pixelBufferAdaptor: AVAssetWriterInputPixelBufferAdaptor?
  internal var frameCount = 0
  internal var startTime: CMTime?
  internal var targetFramesPerSecond = 30.0
  internal var lastFrameTime = CMTime.zero
  internal var savedVideoPath: URL?
  internal var recordingFilePath: URL?

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

  public func dispose() {
    print("DEBUG: Disposing VideoCapture")
    
    // Stop recording if in progress
    if isRecording {
      movieFileOutput.stopRecording()
      isRecording = false
    }
    
    // Stop frame capturing if in progress
    if isCapturingFrames {
      isCapturingFrames = false
      videoWriterInput?.markAsFinished()
      videoWriter?.finishWriting { [weak self] in
        self?.cleanupVideoWriter()
      }
    }
    
    // Stop the capture session
    if captureSession.isRunning {
      captureSession.stopRunning()
    }
    
    // Remove preview layer
    DispatchQueue.main.async { [weak self] in
      self?.previewLayer?.removeFromSuperlayer()
      self?.previewLayer = nil
    }
    
    // Clean up capture session
    captureSession.beginConfiguration()
    
    // Remove all inputs
    for input in captureSession.inputs {
      captureSession.removeInput(input)
    }
    
    // Remove all outputs
    for output in captureSession.outputs {
      captureSession.removeOutput(output)
    }
    
    captureSession.commitConfiguration()
    
    // Clean up delegates and retained objects
    delegate = nil
    bytesPhotoCaptureDelegate = nil
    nativeView = nil
    
    // Reset state variables
    lastCapturedPhoto = nil
    recordingFilePath = nil
    
    cleanupVideoWriter()
    
    print("DEBUG: VideoCapture disposed successfully")
  }
  
  private func cleanupVideoWriter() {
    videoWriter = nil
    videoWriterInput = nil
    pixelBufferAdaptor = nil
    savedVideoPath = nil
    frameCount = 0
    startTime = nil
         lastFrameTime = CMTime.zero
   }
 }

 // MARK: - BytesPhotoCaptureDelegate
 extension VideoCapture {
   // Helper class for photo capture that returns bytes
   class BytesPhotoCaptureDelegate: NSObject, AVCapturePhotoCaptureDelegate {
     private let completion: (Data?, Error?) -> Void
     
     init(completion: @escaping (Data?, Error?) -> Void) {
       self.completion = completion
       super.init()
       print("DEBUG: BytesPhotoCaptureDelegate initialized")
     }
     
     func photoOutput(_ output: AVCapturePhotoOutput, willBeginCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
       print("DEBUG: Will begin photo capture with settings ID: \(resolvedSettings.uniqueID)")
     }
     
     func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
       print("DEBUG: Did finish processing photo")
       
       if let error = error {
         print("DEBUG: Photo capture error: \(error.localizedDescription)")
         completion(nil, error)
         return
       }
       
       guard let imageData = photo.fileDataRepresentation() else {
         let error = NSError(domain: "PhotoCapture", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not get image data"])
         print("DEBUG: Failed to get file data representation")
         completion(nil, error)
         return
       }
       
       print("DEBUG: Photo data extracted successfully, size: \(imageData.count) bytes")
       
       // Return the image data directly
       completion(imageData, nil)
     }
     
     deinit {
       print("DEBUG: BytesPhotoCaptureDelegate deinit")
     }
   }
 } 