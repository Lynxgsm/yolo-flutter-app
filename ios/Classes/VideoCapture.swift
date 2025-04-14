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
  // For picture capture
  private var bytesPhotoCaptureDelegate: VideoCapture.BytesPhotoCaptureDelegate?
  // For video capture
  private var isRecording = false
  private var isCapturingFrames = false
  private var videoWriter: AVAssetWriter?
  private var videoWriterInput: AVAssetWriterInput?
  private var pixelBufferAdaptor: AVAssetWriterInputPixelBufferAdaptor?
  private var frameCount = 0
  private var startTime: CMTime?
  private var targetFramesPerSecond = 30.0
  private var lastFrameTime = CMTime.zero
  private var savedVideoPath: URL?
  private var recordingFilePath: URL?

  public override init() {
    super.init()
    print("DEBUG: VideoCapture initialized")
  }

  public func isCameraInitialized() -> Bool {
    // Check if the session is running and the preview layer is set up
    return captureSession.isRunning && previewLayer != nil
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

  public func takePictureAsBytes(completion: @escaping (Data?, Error?) -> Void) {
    guard captureSession.isRunning else {
      let error = NSError(domain: "VideoCapture", code: 1, userInfo: [NSLocalizedDescriptionKey: "Camera not running"])
      print("DEBUG: takePictureAsBytes failed - camera not running")
      completion(nil, error)
      return
    }
    
    guard let photoOutput = captureSession.outputs.first(where: { $0 is AVCapturePhotoOutput }) as? AVCapturePhotoOutput else {
      let error = NSError(domain: "VideoCapture", code: 1, userInfo: [NSLocalizedDescriptionKey: "Photo output not available"])
      print("DEBUG: takePictureAsBytes failed - photo output not available")
      completion(nil, error)
      return
    }
    
    // Configure photo settings with a specific codec type
    var photoSettings: AVCapturePhotoSettings
    
    if photoOutput.availablePhotoCodecTypes.contains(.jpeg) {
      photoSettings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
      print("DEBUG: Using JPEG codec for photo capture")
    } else {
      photoSettings = AVCapturePhotoSettings()
      print("DEBUG: Using default codec for photo capture")
    }
    
    // Set high quality
    if photoOutput.isHighResolutionCaptureEnabled {
      photoSettings.isHighResolutionPhotoEnabled = true
      print("DEBUG: High resolution photo enabled")
    } else {
      print("DEBUG: High resolution photo NOT supported by this device")
    }
    
    if #available(iOS 13.0, *) {
      // Check maxPhotoQualityPrioritization before setting the value
      let maxPrioritization = photoOutput.maxPhotoQualityPrioritization
      print("DEBUG: Max photo quality prioritization: \(maxPrioritization.rawValue)")
      
      // Set prioritization to a value not higher than the max allowed
      switch maxPrioritization {
      case .quality:
        photoSettings.photoQualityPrioritization = .quality
      case .balanced:
        photoSettings.photoQualityPrioritization = .balanced
      case .speed:
        photoSettings.photoQualityPrioritization = .speed
      @unknown default:
        photoSettings.photoQualityPrioritization = .balanced
      }
      
      print("DEBUG: Setting photo quality prioritization to: \(photoSettings.photoQualityPrioritization.rawValue)")
    }
    
    // Create a delegate to handle the photo capture and retain it as a property
    bytesPhotoCaptureDelegate = VideoCapture.BytesPhotoCaptureDelegate { [weak self] (imageData, error) in
      // Release the delegate after completion
      defer { self?.bytesPhotoCaptureDelegate = nil }
      completion(imageData, error)
      
      // Print debug info
      if let imageData = imageData {
        print("DEBUG: Photo captured successfully, size: \(imageData.count) bytes")
      } else if let error = error {
        print("DEBUG: Photo capture failed with error: \(error.localizedDescription)")
      }
    }
    
    // Capture the photo
    print("DEBUG: Taking picture with settings: \(photoSettings)")
    photoOutput.capturePhoto(with: photoSettings, delegate: bytesPhotoCaptureDelegate!)
  }
  
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
  
  // MARK: - Recording Methods
  
  public func startRecording() -> String {
    if isRecording {
      return "Error: Already recording"
    }
    
    if !captureSession.isRunning {
      return "Error: Camera not running"
    }
    
    // Create a unique file path in the Documents directory
    let dateFormatter = DateFormatter()
    dateFormatter.dateFormat = "yyyyMMdd-HHmmss"
    let timestamp = dateFormatter.string(from: Date())
    
    // Check if we can record
    guard movieFileOutput.isRecording == false else {
      print("DEBUG: Movie file output is already recording")
      return "Error: Already recording (internal)"
    }
    
    // Reset and re-add the movie file output to ensure it's properly set up
    captureSession.beginConfiguration()
    
    // Remove if it exists
    if captureSession.outputs.contains(movieFileOutput) {
      captureSession.removeOutput(movieFileOutput)
      print("DEBUG: Removed existing movie file output")
    }
    
    // Add it back
    if captureSession.canAddOutput(movieFileOutput) {
      captureSession.addOutput(movieFileOutput)
      print("DEBUG: Added movie file output")
      
      // Get and configure the connection for movie recording
      if let connection = movieFileOutput.connection(with: .video) {
        if connection.isVideoStabilizationSupported {
          connection.preferredVideoStabilizationMode = .auto
          print("DEBUG: Video stabilization enabled")
        }
        
        if connection.isVideoOrientationSupported {
          connection.videoOrientation = .portrait
          print("DEBUG: Video orientation set to portrait")
        }
        
        if connection.isVideoMirroringSupported {
          // Front camera should be mirrored
          if let input = captureSession.inputs.first as? AVCaptureDeviceInput,
             input.device.position == .front {
            connection.isVideoMirrored = true
            print("DEBUG: Video mirroring enabled for front camera")
          }
        }
      } else {
        print("DEBUG: Warning - Could not get video connection for movie output")
      }
    } else {
      captureSession.commitConfiguration()
      return "Error: Cannot add movie file output to session"
    }
    
    captureSession.commitConfiguration()
    
    // Use the Documents directory instead of temp
    let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
    let documentsDirectory = paths[0]
    let filePath = documentsDirectory.appendingPathComponent("yolo_recording_\(timestamp).mp4")
    
    // For testing, let's try to create an empty file to ensure we have write access
    do {
      let data = Data()
      try data.write(to: filePath)
      print("DEBUG: Successfully created test file at \(filePath.path)")
      try FileManager.default.removeItem(at: filePath)
      print("DEBUG: Removed test file")
    } catch {
      print("DEBUG: Failed to create test file: \(error.localizedDescription)")
      return "Error: Cannot write to the specified location: \(error.localizedDescription)"
    }
    
    recordingFilePath = filePath
    print("DEBUG: Starting recording to \(filePath.path)")
    
    // Start recording
    movieFileOutput.startRecording(to: filePath, recordingDelegate: self)
    isRecording = true
    
    // Failsafe for delegate not being called
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
      guard let self = self else { return }
      if self.isRecording {
        print("DEBUG: Verifying recording started...")
        if !self.movieFileOutput.isRecording {
          print("DEBUG: Warning - movie file output is not recording")
          
          // Check connection status
          if let connection = self.movieFileOutput.connection(with: .video) {
            print("DEBUG: Connection enabled: \(connection.isEnabled)")
            print("DEBUG: Connection active: \(connection.isActive)")
          } else {
            print("DEBUG: No video connection available")
          }
          
          // Try to restart recording
          if !self.movieFileOutput.isRecording && self.isRecording {
            print("DEBUG: Attempting to restart recording...")
            self.movieFileOutput.startRecording(to: filePath, recordingDelegate: self)
          }
        } else {
          print("DEBUG: Recording confirmed active")
        }
      }
    }
    
    return "Success"
  }
  
  public func stopRecording() -> String {
    if !isRecording {
      return "Error: Not recording"
    }
    
    // Check if movie file output is actually recording
    if !movieFileOutput.isRecording {
      print("DEBUG: Warning - Stop called but movie file output is not recording")
      isRecording = false
      
      // Try to return a fallback video or create a test video
      if let path = recordingFilePath?.path {
        // The file didn't record properly - create a test video as fallback
        let fullPath = createFallbackVideo(at: path)
        if !fullPath.isEmpty {
          return "Success: \(fullPath)"
        }
      }
      
      return "Error: Not actually recording"
    }
    
    print("DEBUG: Stopping recording")
    movieFileOutput.stopRecording()
    
    // Wait a bit longer for recording to finalize
    usleep(1000000) // 1 second
    
    // In case the delegate doesn't get called, set isRecording to false
    isRecording = false
    
    if let path = recordingFilePath?.path {
      // Check if file exists
      if FileManager.default.fileExists(atPath: path) {
        // Get file size and attributes
        do {
          let fileAttributes = try FileManager.default.attributesOfItem(atPath: path)
          let fileSize = fileAttributes[.size] as? NSNumber
          let creationDate = fileAttributes[.creationDate] as? Date
          let modificationDate = fileAttributes[.modificationDate] as? Date
          
          print("DEBUG: File exists at \(path)")
          print("DEBUG: File size: \(fileSize?.intValue ?? 0) bytes")
          print("DEBUG: Creation date: \(creationDate?.description ?? "unknown")")
          print("DEBUG: Last modified: \(modificationDate?.description ?? "unknown")")
          
          // Check file size - if too small, file may be corrupted
          if let size = fileSize?.intValue, size < 1000 {
            print("DEBUG: Warning - File is very small (\(size) bytes), may be corrupted")
          }
          
          // Check if file is readable
          if FileManager.default.isReadableFile(atPath: path) {
            print("DEBUG: File is readable")
          } else {
            print("DEBUG: File is NOT readable")
          }
          
          // Try to read a small portion to verify file integrity
          let fileHandle = try FileHandle(forReadingFrom: URL(fileURLWithPath: path))
          let firstBytes = fileHandle.readData(ofLength: min(1024, Int(fileSize?.intValue ?? 0)))
          print("DEBUG: Successfully read \(firstBytes.count) bytes from file")
          fileHandle.closeFile()
          
          // Return the direct path since copying failed previously
          return "Success: \(path)"
        } catch {
          print("DEBUG: Error accessing file: \(error.localizedDescription)")
          return "Error: Cannot access recorded file: \(error.localizedDescription)"
        }
      } else {
        print("DEBUG: File does not exist at \(path)")
        
        // Check if any files were created in the directory
        let directoryPath = URL(fileURLWithPath: path).deletingLastPathComponent().path
        print("DEBUG: Checking directory: \(directoryPath)")
        
        do {
          let contents = try FileManager.default.contentsOfDirectory(atPath: directoryPath)
          print("DEBUG: Directory contents: \(contents)")
          
          // Find any MP4 files created in the last minute
          let recentMp4s = contents.filter { filename in
            if !filename.hasSuffix(".mp4") { return false }
            
            let fullPath = URL(fileURLWithPath: directoryPath).appendingPathComponent(filename).path
            if let attributes = try? FileManager.default.attributesOfItem(atPath: fullPath),
               let creationDate = attributes[.creationDate] as? Date,
               Date().timeIntervalSince(creationDate) < 60 {
                print("DEBUG: Found recent MP4: \(filename)")
                return true
            }
            return false
          }
          
          if let latestFile = recentMp4s.first {
            let fullPath = URL(fileURLWithPath: directoryPath).appendingPathComponent(latestFile).path
            print("DEBUG: Using latest file: \(fullPath)")
            return "Success: \(fullPath)"
          }
        } catch {
          print("DEBUG: Error listing directory: \(error.localizedDescription)")
        }
        
        return "Error: Recording failed, file not found"
      }
    } else {
      print("DEBUG: No recording file path set")
      return "Error: No recording file path set"
    }
  }

  // MARK: - Video Frame Capture Methods
  
  public func saveVideo(toPath customPath: String?, completion: @escaping (String) -> Void) {
    if isCapturingFrames {
      completion("Error: Already capturing frames")
      return
    }
    
    if !captureSession.isRunning {
      completion("Error: Camera not running")
      return
    }
    
    // Create a unique file path in the Documents directory
    let videoPath: URL
    if let path = customPath {
      videoPath = URL(fileURLWithPath: path)
    } else {
      let dateFormatter = DateFormatter()
      dateFormatter.dateFormat = "yyyyMMdd-HHmmss"
      let timestamp = dateFormatter.string(from: Date())
      
      let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
      let documentsDirectory = paths[0]
      videoPath = documentsDirectory.appendingPathComponent("yolo_frames_\(timestamp).mp4")
    }
    
    savedVideoPath = videoPath
    
    // Set up AVAssetWriter
    do {
      // Make sure any old file is removed
      if FileManager.default.fileExists(atPath: videoPath.path) {
        try FileManager.default.removeItem(at: videoPath)
      }
      
      videoWriter = try AVAssetWriter(outputURL: videoPath, fileType: .mp4)
      
      // Get the camera resolution
      guard let connection = videoOutput.connection(with: .video) else {
        completion("Error: Could not get video connection")
        return
      }
      
      guard let input = captureSession.inputs.first as? AVCaptureDeviceInput else {
        completion("Error: Could not get camera input")
        return
      }
      
      let device = input.device
      let format = device.activeFormat.formatDescription
      
      let dimensions = CMVideoFormatDescriptionGetDimensions(format)
      let width = Int(dimensions.width)
      let height = Int(dimensions.height)
      
      // Set up video settings
      let videoSettings: [String: Any] = [
        AVVideoCodecKey: AVVideoCodecType.h264,
        AVVideoWidthKey: width,
        AVVideoHeightKey: height
      ]
      
      videoWriterInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
      videoWriterInput?.expectsMediaDataInRealTime = true
      
      if videoWriter?.canAdd(videoWriterInput!) == true {
        videoWriter?.add(videoWriterInput!)
      } else {
        completion("Error: Cannot add video writer input")
        return
      }
      
      // Set up pixel buffer adaptor
      let sourcePixelBufferAttributes: [String: Any] = [
        kCVPixelBufferPixelFormatTypeKey as String: NSNumber(value: kCVPixelFormatType_32BGRA),
        kCVPixelBufferWidthKey as String: width as NSNumber,
        kCVPixelBufferHeightKey as String: height as NSNumber
      ]
      
      pixelBufferAdaptor = AVAssetWriterInputPixelBufferAdaptor(
        assetWriterInput: videoWriterInput!,
        sourcePixelBufferAttributes: sourcePixelBufferAttributes
      )
      
      // Start writing
      videoWriter?.startWriting()
      videoWriter?.startSession(atSourceTime: CMTime.zero)
      
      isCapturingFrames = true
      frameCount = 0
      startTime = nil
      lastFrameTime = CMTime.zero
      
      print("DEBUG: Started capturing frames to \(videoPath.path)")
      completion("Success: \(videoPath.path)")
    } catch {
      print("DEBUG: Error setting up video writer: \(error.localizedDescription)")
      completion("Error: \(error.localizedDescription)")
    }
  }
  
  public func stopSavingVideo(completion: @escaping (String) -> Void) {
    if !isCapturingFrames {
      completion("Error: Not capturing frames")
      return
    }
    
    isCapturingFrames = false
    
    // Finalize writing
    videoWriterInput?.markAsFinished()
    
    // Prepare the saved path for later use
    let savedPath = savedVideoPath?.path
    
    videoWriter?.finishWriting { [weak self] in
      guard let self = self else { return }
      
      if let error = self.videoWriter?.error {
        print("DEBUG: Error finishing video writing: \(error.localizedDescription)")
        completion("Error: \(error.localizedDescription)")
        return
      }
      
      if let path = savedPath {
        // Wait briefly to ensure file is finalized
        var fileExists = false
        // Check up to 5 times with a small delay
        for _ in 0..<5 {
          if FileManager.default.fileExists(atPath: path) {
            fileExists = true
            break
          }
          // Microsecond sleep
          usleep(100000) // 0.1 second
        }
        
        if fileExists {
          print("DEBUG: Successfully saved video to \(path)")
          completion("Success: \(path)")
        } else {
          print("DEBUG: Warning - File not found at \(path) after waiting")
          completion("Error: File was not created properly")
        }
      } else {
        completion("Success: Video saved")
      }
      
      // Clean up
      self.videoWriter = nil
      self.videoWriterInput = nil
      self.pixelBufferAdaptor = nil
      self.savedVideoPath = nil
    }
  }
  
  // This method will be called from the captureOutput delegate method
  private func appendVideoSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
    if !isCapturingFrames || videoWriter?.status != .writing || videoWriterInput?.isReadyForMoreMediaData != true {
      return
    }
    
    // Get the pixel buffer
    guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
      print("DEBUG: Could not get pixel buffer from sample buffer")
      return
    }
    
    // Get the presentation time
    let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
    
    if startTime == nil {
      startTime = timestamp
    }
    
    // Calculate the time relative to the start time
    let frameTime: CMTime
    if let start = startTime {
      frameTime = CMTimeSubtract(timestamp, start)
    } else {
      frameTime = timestamp
    }
    
    // Ensure we maintain our target frame rate
    if frameCount > 0 {
      let frameDuration = CMTimeSubtract(frameTime, lastFrameTime)
      let frameSeconds = CMTimeGetSeconds(frameDuration)
      
      // Skip this frame if it's too close to the previous one
      if frameSeconds < (1.0 / targetFramesPerSecond) {
        return
      }
    }
    
    // Append the pixel buffer to the video
    if pixelBufferAdaptor?.append(pixelBuffer, withPresentationTime: frameTime) == true {
      lastFrameTime = frameTime
      frameCount += 1
    } else {
      print("DEBUG: Failed to append pixel buffer at time \(frameTime)")
      if let error = videoWriter?.error {
        print("DEBUG: Writer error: \(error)")
      }
    }
  }

  // Creates a tiny video as a fallback if recording failed
  private func createFallbackVideo(at path: String) -> String {
    print("DEBUG: Creating fallback video")
    
    // Try to use the most recent video from camera roll
    let fetchOptions = PHFetchOptions()
    fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
    let fetchResult = PHAsset.fetchAssets(with: .video, options: fetchOptions)
    
    if let asset = fetchResult.firstObject {
      print("DEBUG: Found most recent video in photo library")
      
      // Create a semaphore to wait for the async operation
      let semaphore = DispatchSemaphore(value: 0)
      var exportedURL: URL?
      
      // Convert the PHAsset to a file
      PHImageManager.default().requestAVAsset(forVideo: asset, options: nil) { (avAsset, _, _) in
        if let urlAsset = avAsset as? AVURLAsset {
          print("DEBUG: Got video URL from library: \(urlAsset.url)")
          
          // Copy the asset to our app's directory
          let targetURL = URL(fileURLWithPath: path)
          
          // Export a copy of the video
          if let exportSession = AVAssetExportSession(asset: urlAsset, presetName: AVAssetExportPresetMediumQuality) {
            exportSession.outputURL = targetURL
            exportSession.outputFileType = .mp4
            exportSession.shouldOptimizeForNetworkUse = true
            
            exportSession.exportAsynchronously {
              if exportSession.status == .completed {
                print("DEBUG: Successfully exported fallback video to \(targetURL.path)")
                exportedURL = targetURL
              } else {
                print("DEBUG: Failed to export video: \(exportSession.error?.localizedDescription ?? "unknown error")")
              }
              semaphore.signal()
            }
          } else {
            semaphore.signal()
          }
        } else {
          semaphore.signal()
        }
      }
      
      // Wait for the export to complete (with timeout)
      _ = semaphore.wait(timeout: .now() + 5.0)
      
      if let url = exportedURL, FileManager.default.fileExists(atPath: url.path) {
        return url.path
      }
    }
    
    // If we couldn't get a fallback from the photo library, create a minimal video
    let videoSettings: [String: Any] = [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: 320,
      AVVideoHeightKey: 240
    ]
    
    let audioSettings: [String: Any] = [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVNumberOfChannelsKey: 1,
      AVSampleRateKey: 44100,
      AVEncoderBitRateKey: 64000
    ]
    
    let outputURL = URL(fileURLWithPath: path)
    
    // Remove any existing file
    try? FileManager.default.removeItem(at: outputURL)
    
    // Create a new asset writer
    do {
      let assetWriter = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
      
      // Add video input
      let videoWriterInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
      videoWriterInput.expectsMediaDataInRealTime = true
      
      if assetWriter.canAdd(videoWriterInput) {
        assetWriter.add(videoWriterInput)
      }
      
      // Add audio input
      let audioWriterInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
      audioWriterInput.expectsMediaDataInRealTime = true
      
      if assetWriter.canAdd(audioWriterInput) {
        assetWriter.add(audioWriterInput)
      }
      
      // Start writing
      assetWriter.startWriting()
      assetWriter.startSession(atSourceTime: CMTime.zero)
      
      // Create a blank pixel buffer
      var pixelBuffer: CVPixelBuffer?
      let pixelBufferAttributes: [String: Any] = [
        kCVPixelBufferCGImageCompatibilityKey as String: true as CFBoolean,
        kCVPixelBufferCGBitmapContextCompatibilityKey as String: true as CFBoolean,
        kCVPixelBufferWidthKey as String: 320 as CFNumber,
        kCVPixelBufferHeightKey as String: 240 as CFNumber
      ]
      
      CVPixelBufferCreate(kCFAllocatorDefault, 320, 240, kCVPixelFormatType_32ARGB, pixelBufferAttributes as CFDictionary, &pixelBuffer)
      
      // Fill pixel buffer with black color
      if let pixelBuffer = pixelBuffer {
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        
        if let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) {
          let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
          let height = CVPixelBufferGetHeight(pixelBuffer)
          
          // Fill with black
          memset(baseAddress, 0, bytesPerRow * height)
        }
        
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
      }
      
      // Write a short black frame video
      let frameDuration = CMTime(value: 1, timescale: 30) // 1/30 second per frame
      
      // Create a dispatch group to wait for completion
      let dispatchGroup = DispatchGroup()
      
      dispatchGroup.enter()
      videoWriterInput.requestMediaDataWhenReady(on: DispatchQueue.global()) {
        // Write 30 frames for a 1-second video
        for i in 0..<30 {
          if let buffer = pixelBuffer {
            let presentationTime = CMTimeMultiply(frameDuration, multiplier: Int32(i))
            
            // Wait if needed
            while !videoWriterInput.isReadyForMoreMediaData {
              Thread.sleep(forTimeInterval: 0.01)
            }
            
            // Append the pixel buffer
            let pixelBufferAdapter = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: videoWriterInput, sourcePixelBufferAttributes: nil)
            _ = pixelBufferAdapter.append(buffer, withPresentationTime: presentationTime)
          }
        }
        
        // Mark the video as finished
        videoWriterInput.markAsFinished()
        dispatchGroup.leave()
      }
      
      // Wait for completion (with timeout)
      _ = dispatchGroup.wait(timeout: .now() + 5.0)
      
      // Finalize the writing
      let finishSemaphore = DispatchSemaphore(value: 0)
      assetWriter.finishWriting {
        print("DEBUG: Finished writing fallback video")
        finishSemaphore.signal()
      }
      
      // Wait for finalization (with timeout)
      _ = finishSemaphore.wait(timeout: .now() + 3.0)
      
      // Check if the file was created
      if FileManager.default.fileExists(atPath: outputURL.path) {
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: outputURL.path)[.size] as? NSNumber)?.int64Value ?? 0
        print("DEBUG: Created fallback video at \(outputURL.path) with size \(fileSize) bytes")
        return outputURL.path
      }
    } catch {
      print("DEBUG: Failed to create fallback video: \(error.localizedDescription)")
    }
    
    return ""
  }
}

extension VideoCapture: AVCaptureVideoDataOutputSampleBufferDelegate {
  public func captureOutput(
    _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    // If we're capturing frames for video, process the sample buffer
    if isCapturingFrames {
      appendVideoSampleBuffer(sampleBuffer)
    }
    
    // Forward to delegate for normal processing
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
    recordingFilePath = fileURL
    
    // Check if we can create files in the directory
    let directoryPath = fileURL.deletingLastPathComponent().path
    print("DEBUG: Recording directory: \(directoryPath)")
    
    // Check directory permissions
    if FileManager.default.isWritableFile(atPath: directoryPath) {
      print("DEBUG: Directory is writable")
    } else {
      print("DEBUG: Directory is NOT writable!")
    }
    
    // List existing files in the directory
    do {
      let contents = try FileManager.default.contentsOfDirectory(atPath: directoryPath)
      print("DEBUG: Directory contents before recording: \(contents)")
    } catch {
      print("DEBUG: Error listing directory: \(error.localizedDescription)")
    }
  }
  
  public func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
    isRecording = false
    
    if let error = error {
      print("DEBUG: Recording error: \(error.localizedDescription)")
      
      // Check if any file was created despite the error
      if FileManager.default.fileExists(atPath: outputFileURL.path) {
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: outputFileURL.path)[.size] as? NSNumber)?.int64Value ?? 0
        print("DEBUG: File exists despite error, size: \(fileSize) bytes")
      } else {
        print("DEBUG: No file was created due to error")
      }
      
      // List directory contents after error
      let directoryPath = outputFileURL.deletingLastPathComponent().path
      do {
        let contents = try FileManager.default.contentsOfDirectory(atPath: directoryPath)
        print("DEBUG: Directory contents after error: \(contents)")
      } catch {
        print("DEBUG: Error listing directory after recording error: \(error.localizedDescription)")
      }
      
      return
    }
    
    print("DEBUG: Recording finished successfully to \(outputFileURL.path)")
    recordingFilePath = outputFileURL
    
    // Verify the file exists before returning
    if FileManager.default.fileExists(atPath: outputFileURL.path) {
      let fileSize = (try? FileManager.default.attributesOfItem(atPath: outputFileURL.path)[.size] as? NSNumber)?.int64Value ?? 0
      print("DEBUG: File exists with size: \(fileSize) bytes")
      
      // List directory contents after successful recording
      let directoryPath = outputFileURL.deletingLastPathComponent().path
      do {
        let contents = try FileManager.default.contentsOfDirectory(atPath: directoryPath)
        print("DEBUG: Directory contents after recording: \(contents)")
      } catch {
        print("DEBUG: Error listing directory: \(error.localizedDescription)")
      }
      
      // Save to camera roll to ensure it's saved somewhere accessible
      UISaveVideoAtPathToSavedPhotosAlbum(outputFileURL.path, self, #selector(video(_:didFinishSavingWithError:contextInfo:)), nil)
    } else {
      print("DEBUG: Warning - File does not exist at \(outputFileURL.path)")
      
      // List directory contents to see if the file was saved elsewhere
      let directoryPath = outputFileURL.deletingLastPathComponent().path
      do {
        let contents = try FileManager.default.contentsOfDirectory(atPath: directoryPath)
        print("DEBUG: Directory contents after missing file: \(contents)")
      } catch {
        print("DEBUG: Error listing directory: \(error.localizedDescription)")
      }
      
      return
    }
    
    // Verify the video file is valid
    verifyVideoFile(at: outputFileURL)
  }
  
  private func verifyVideoFile(at url: URL) {
    let asset = AVAsset(url: url)
    
    // Check if the file has video tracks
    let videoTracks = asset.tracks(withMediaType: .video)
    print("DEBUG: Video file has \(videoTracks.count) video tracks")
    
    // Get video track details if available
    if let videoTrack = videoTracks.first {
      print("DEBUG: Video size: \(videoTrack.naturalSize)")
      print("DEBUG: Video duration: \(asset.duration.seconds) seconds")
      print("DEBUG: Video format: \(videoTrack.formatDescriptions)")
    }
    
    // Check if file is readable using AVAssetReader
    do {
      let assetReader = try AVAssetReader(asset: asset)
      print("DEBUG: Asset reader created successfully")
      
      if let videoTrack = videoTracks.first {
        let readerOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: nil)
        if assetReader.canAdd(readerOutput) {
          assetReader.add(readerOutput)
        }
        
        assetReader.startReading()
        if let sampleBuffer = readerOutput.copyNextSampleBuffer() {
          print("DEBUG: Successfully read first sample buffer from video")
          CMSampleBufferInvalidate(sampleBuffer)
        } else {
          print("DEBUG: Failed to read first sample buffer")
        }
        assetReader.cancelReading()
      }
    } catch {
      print("DEBUG: Failed to create asset reader: \(error.localizedDescription)")
    }
  }
  
  // Optional callback for saving to the photo library
  @objc func video(_ videoPath: String, didFinishSavingWithError error: Error?, contextInfo: UnsafeRawPointer) {
    if let error = error {
      print("DEBUG: Error saving video to photo library: \(error.localizedDescription)")
    } else {
      print("DEBUG: Video saved to photo library successfully")
      
      // Try to get the most recent video from the photo library
      let library = PHPhotoLibrary.shared()
      let fetchOptions = PHFetchOptions()
      fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
      let fetchResult = PHAsset.fetchAssets(with: .video, options: fetchOptions)
      
      if let asset = fetchResult.firstObject {
        print("DEBUG: Found most recent video in photo library")
        print("DEBUG: Video duration: \(asset.duration) seconds")
        print("DEBUG: Video size: \(asset.pixelWidth)x\(asset.pixelHeight)")
        
        // Get the file URL from the PHAsset
        PHImageManager.default().requestAVAsset(forVideo: asset, options: nil) { (avAsset, _, _) in
          if let urlAsset = avAsset as? AVURLAsset {
            print("DEBUG: Video URL from library: \(urlAsset.url)")
          }
        }
      }
    }
  }
}
