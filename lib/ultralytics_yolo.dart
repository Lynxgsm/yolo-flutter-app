import 'dart:async';

import 'package:ultralytics_yolo/camera_preview/ultralytics_yolo_camera_preview.dart';
import 'package:ultralytics_yolo/predict/detect/detected_object.dart';
import 'package:ultralytics_yolo/predict/detect/object_detector.dart';
import 'package:ultralytics_yolo/predict/classify/classification_result.dart';
import 'package:ultralytics_yolo/predict/classify/image_classifier.dart';
import 'package:ultralytics_yolo/ultralytics_yolo_platform_interface.dart';
import 'package:ultralytics_yolo/yolo_model.dart';

// Export all the models
export 'yolo_model.dart';
export 'predict/classify/classification_result.dart';
export 'predict/classify/image_classifier.dart';
export 'predict/detect/detected_object.dart';
export 'predict/detect/object_detector.dart';
export 'camera_preview/ultralytics_yolo_camera_preview.dart';
export 'camera_preview/ultralytics_yolo_camera_controller.dart';
export 'predict/classify/classification_result_overlay.dart';
export 'predict/detect/detect.dart';
export 'predict/predict.dart';

/// Main class for the Ultralytics YOLO plugin
class UltralyticsYolo {
  final UltralyticsYoloPlatform _platform = UltralyticsYoloPlatform.instance;

  /// Get the instance of the [UltralyticsYoloPlatform]
  UltralyticsYoloPlatform get platform => _platform;

  /// Captures the current frame with optional crop to match SafeArea boundaries.
  ///
  /// [paddingTop], [paddingBottom], [paddingLeft], and [paddingRight] are the SafeArea insets in pixels.
  /// Get these values from MediaQuery.of(context).padding
  Future<String?> getVideoCaptureWithSafeArea({
    double paddingTop = 0,
    double paddingBottom = 0,
    double paddingLeft = 0,
    double paddingRight = 0,
    double? viewWidth,
    double? viewHeight,
  }) async {
    // Get screen dimensions from the plugin if not provided
    final displayWidth = viewWidth ?? 0;
    final displayHeight = viewHeight ?? 0;

    if (displayWidth <= 0 || displayHeight <= 0) {
      return _platform.getVideoCapture(); // Fall back to uncropped capture
    }

    final cropRect = <String, double>{
      'x': paddingLeft,
      'y': paddingTop,
      'width': displayWidth - paddingLeft - paddingRight,
      'height': displayHeight - paddingTop - paddingBottom,
    };

    return _platform.getVideoCapture(cropRect: cropRect);
  }
}
