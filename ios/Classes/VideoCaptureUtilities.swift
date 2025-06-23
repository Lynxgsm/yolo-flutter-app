import AVFoundation
import CoreVideo
import UIKit

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

public protocol VideoCaptureDelegate: AnyObject {
  func videoCapture(_ capture: VideoCapture, didCaptureVideoFrame: CMSampleBuffer)
} 