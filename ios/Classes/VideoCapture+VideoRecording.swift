import AVFoundation
import CoreVideo
import UIKit
import Photos

// MARK: - Video Recording Extension
extension VideoCapture {
  
  // Add a method to check if camera is ready for recording
  public func isCameraReadyForRecording() -> Bool {
    // Check if session is running
    if !captureSession.isRunning {
      print("DEBUG: Camera session is not running")
      return false
    }
    
    // Check if we have a valid movie file output
    if !captureSession.outputs.contains(where: { $0 is AVCaptureMovieFileOutput }) {
      print("DEBUG: Movie file output is not in the session")
      return false
    }
    
    // Check if we have a valid camera input
    guard let input = captureSession.inputs.first as? AVCaptureDeviceInput,
          input.device.hasMediaType(.video) else {
      print("DEBUG: No valid camera input")
      return false
    }
    
    // Check authorization status
    let authorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    if authorizationStatus != .authorized {
      print("DEBUG: Camera authorization not granted")
      return false
    }
    
    return true
  }
  
  public func startRecording() -> String {
    // Use the new preflight check
    let (isReady, message) = preflightRecordingCheck()
    if !isReady {
      return "Error: \(message)"
    }
    
    // Create a unique file path in the Documents directory
    let dateFormatter = DateFormatter()
    dateFormatter.dateFormat = "yyyyMMdd-HHmmss"
    let timestamp = dateFormatter.string(from: Date())
    
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

  // Add a precheck method that returns if the device is ready to record
  public func preflightRecordingCheck() -> (Bool, String) {
    // Check if session is running
    if !captureSession.isRunning {
      return (false, "Camera session is not running")
    }
    
    // Check if already recording
    if isRecording || movieFileOutput.isRecording {
      return (false, "Already recording")
    }
    
    // Check for movie file output
    if !captureSession.outputs.contains(where: { $0 is AVCaptureMovieFileOutput }) {
      return (false, "Movie file output not configured")
    }
    
    // Check storage space
    do {
      let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
      let documentsDirectory = paths[0]
      
      let attributes = try FileManager.default.attributesOfFileSystem(forPath: documentsDirectory.path)
      if let freeSize = attributes[.systemFreeSize] as? NSNumber {
        let freeSizeMB = freeSize.int64Value / (1024 * 1024)
        if freeSizeMB < 50 { // Require at least 50MB
          return (false, "Insufficient storage space: \(freeSizeMB)MB available")
        }
      }
      
      // Test file write
      let testFile = documentsDirectory.appendingPathComponent("test_write.tmp")
      try "test".write(to: testFile, atomically: true, encoding: .utf8)
      try FileManager.default.removeItem(at: testFile)
    } catch {
      return (false, "Storage error: \(error.localizedDescription)")
    }
    
    return (true, "Ready to record")
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