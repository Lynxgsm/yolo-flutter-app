import AVFoundation
import CoreVideo
import UIKit
import Photos

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate
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

// MARK: - AVCapturePhotoCaptureDelegate
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

// MARK: - AVCaptureFileOutputRecordingDelegate
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
} 