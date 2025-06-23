import AVFoundation
import CoreVideo
import UIKit

// MARK: - Photo Capture Extension
extension VideoCapture {
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
} 