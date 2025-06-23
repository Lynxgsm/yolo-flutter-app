import AVFoundation
import CoreVideo
import UIKit

// MARK: - Diagnostics Extension
extension VideoCapture {
  
  // Add a method to check device capabilities for video recording
  public func getDeviceCapabilities() -> [String: Any] {
    var capabilities = [String: Any]()
    
    // Check if capture session is configured
    capabilities["isSessionConfigured"] = !captureSession.inputs.isEmpty
    capabilities["isSessionRunning"] = captureSession.isRunning
    
    // Get camera device info if available
    if let input = captureSession.inputs.first as? AVCaptureDeviceInput {
      let device = input.device
      capabilities["devicePosition"] = device.position.rawValue
      capabilities["deviceType"] = device.deviceType.rawValue
      
      // Check available formats
      let availableFormats = device.formats
      var formatInfo = [[String: Any]]()
      
      for format in availableFormats {
        let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        let maxFPS = format.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 0
        
        formatInfo.append([
          "width": dimensions.width,
          "height": dimensions.height,
          "maxFPS": maxFPS
        ])
      }
      
      capabilities["availableFormats"] = formatInfo
      
      // Get current active format
      let activeFormat = device.activeFormat
      let activeDimensions = CMVideoFormatDescriptionGetDimensions(activeFormat.formatDescription)
      
      capabilities["activeFormat"] = [
        "width": activeDimensions.width,
        "height": activeDimensions.height,
        "maxFPS": activeFormat.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 0
      ]
      
      // Check recording capabilities
      capabilities["hasFlash"] = device.hasFlash
      capabilities["hasTorch"] = device.hasTorch
      capabilities["isAdjustingFocus"] = device.isAdjustingFocus
      capabilities["focusMode"] = device.focusMode.rawValue
      
      // Get available zoom range
      capabilities["minZoomFactor"] = device.minAvailableVideoZoomFactor
      capabilities["maxZoomFactor"] = device.maxAvailableVideoZoomFactor
      capabilities["currentZoomFactor"] = device.videoZoomFactor
    }
    
    // Check session preset
    capabilities["sessionPreset"] = captureSession.sessionPreset.rawValue
    
    // Check outputs
    capabilities["hasVideoOutput"] = captureSession.outputs.contains(where: { $0 is AVCaptureVideoDataOutput })
    capabilities["hasPhotoOutput"] = captureSession.outputs.contains(where: { $0 is AVCapturePhotoOutput })
    capabilities["hasMovieOutput"] = captureSession.outputs.contains(where: { $0 is AVCaptureMovieFileOutput })
    
    // Check recording capabilities
    capabilities["isRecording"] = isRecording
    capabilities["isCapturingFrames"] = isCapturingFrames
    
    // Check file system
    if let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
      capabilities["documentsPath"] = documentsDirectory.path
      capabilities["isDocumentsWritable"] = FileManager.default.isWritableFile(atPath: documentsDirectory.path)
      
      // Check available space
      do {
        let attributes = try FileManager.default.attributesOfFileSystem(forPath: documentsDirectory.path)
        if let freeSize = attributes[.systemFreeSize] as? NSNumber {
          capabilities["freeStorageBytes"] = freeSize.int64Value
          capabilities["freeStorageMB"] = freeSize.int64Value / (1024 * 1024)
        }
      } catch {
        capabilities["storageCheckError"] = error.localizedDescription
      }
    }
    
    return capabilities
  }

  // Add a method to get camera status in a human-readable format
  public func getCameraStatus() -> [String: String] {
    let capabilities = getDeviceCapabilities()
    var status = [String: String]()
    
    // Check if session is configured
    if capabilities["isSessionConfigured"] as? Bool == true {
      status["configuration"] = "Configured"
    } else {
      status["configuration"] = "Not configured"
    }
    
    // Check if session is running
    if capabilities["isSessionRunning"] as? Bool == true {
      status["running"] = "Running"
    } else {
      status["running"] = "Not running"
    }
    
    // Check camera position
    if let position = capabilities["devicePosition"] as? Int {
      switch position {
      case 1: status["position"] = "Front"
      case 2: status["position"] = "Back"
      default: status["position"] = "Unknown"
      }
    } else {
      status["position"] = "No camera"
    }
    
    // Check active format
    if let activeFormat = capabilities["activeFormat"] as? [String: Any],
       let width = activeFormat["width"] as? Int,
       let height = activeFormat["height"] as? Int {
      status["resolution"] = "\(width)x\(height)"
    } else {
      status["resolution"] = "Unknown"
    }
    
    // Check outputs
    if capabilities["hasMovieOutput"] as? Bool == true {
      status["movieOutput"] = "Available"
    } else {
      status["movieOutput"] = "Not available"
    }
    
    // Check recording state
    if capabilities["isRecording"] as? Bool == true {
      status["recording"] = "Recording in progress"
    } else {
      status["recording"] = "Not recording"
    }
    
    // Check storage
    if let freeStorageMB = capabilities["freeStorageMB"] as? Int64 {
      status["storage"] = "\(freeStorageMB) MB free"
      
      if freeStorageMB < 50 {
        status["storageWarning"] = "Low storage (less than 50MB)"
      } else if freeStorageMB < 200 {
        status["storageWarning"] = "Limited storage (less than 200MB)"
      } else {
        status["storageWarning"] = "Sufficient storage"
      }
    } else {
      status["storage"] = "Unknown"
    }
    
    // Overall readiness check
    let (isReady, message) = preflightRecordingCheck()
    status["ready"] = isReady ? "Ready to record" : "Not ready"
    status["readyDetail"] = message
    
    return status
  }
  
  // Add convenience method to check if camera can record (can be exposed to Flutter)
  public func canStartRecording() -> [String: Any] {
    let (isReady, message) = preflightRecordingCheck()
    
    var result: [String: Any] = [
      "canRecord": isReady,
      "message": message
    ]
    
    // If there's an issue, include more detailed diagnostics
    if !isReady {
      result["status"] = getCameraStatus()
      result["diagnostics"] = getDeviceCapabilities()
    }
    
    return result
  }

  // Add a comprehensive setup check method
  public func ensureCameraReadyForRecording(completion: @escaping (Bool, String) -> Void) {
    // First check permission
    requestCameraPermission { [weak self] permissionGranted in
      guard let self = self else {
        completion(false, "Error: VideoCapture object released")
        return
      }
      
      if !permissionGranted {
        completion(false, "Error: Camera permission not granted")
        return
      }
      
      // If camera is not running, try to start it
      if !self.captureSession.isRunning {
        print("DEBUG: Starting camera session")
        self.start()
        
        // Give the camera a moment to start up
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
          guard let self = self else {
            completion(false, "Error: VideoCapture object released")
            return
          }
          
          if self.captureSession.isRunning {
            // Now check if everything else is ready
            let isReady = self.isCameraReadyForRecording()
            completion(isReady, isReady ? "Success" : "Error: Camera not ready for recording")
          } else {
            completion(false, "Error: Failed to start camera session")
          }
        }
      } else {
        // Camera is already running, check if everything else is ready
        let isReady = self.isCameraReadyForRecording()
        completion(isReady, isReady ? "Success" : "Error: Camera not ready for recording")
      }
    }
  }

  // Add a method to check and request camera permissions
  public func requestCameraPermission(completion: @escaping (Bool) -> Void) {
    let authorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    
    switch authorizationStatus {
    case .authorized:
      // Permission already granted
      print("DEBUG: Camera permission already granted")
      completion(true)
      
    case .notDetermined:
      // Request permission
      print("DEBUG: Requesting camera permission")
      AVCaptureDevice.requestAccess(for: .video) { granted in
        print("DEBUG: Camera permission \(granted ? "granted" : "denied")")
        DispatchQueue.main.async {
          completion(granted)
        }
      }
      
    case .denied, .restricted:
      // Permission denied
      print("DEBUG: Camera permission denied or restricted")
      completion(false)
      
    @unknown default:
      print("DEBUG: Unknown camera permission status")
      completion(false)
    }
  }
} 