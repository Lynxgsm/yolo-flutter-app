import AVFoundation
import CoreVideo
import UIKit

// MARK: - Frame Capture Extension
extension VideoCapture {
  
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
  internal func appendVideoSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
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
} 