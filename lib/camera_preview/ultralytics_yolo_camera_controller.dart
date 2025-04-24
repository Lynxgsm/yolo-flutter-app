import 'package:flutter/foundation.dart';
import 'package:ultralytics_yolo/ultralytics_yolo_platform_interface.dart';

/// The state of the camera
class UltralyticsYoloCameraValue {
  /// Constructor to create an instance of [UltralyticsYoloCameraValue]
  UltralyticsYoloCameraValue({
    required this.lensDirection,
    required this.strokeWidth,
  });

  /// The direction of the camera lens
  final int lensDirection;

  /// The width of the stroke used to draw the bounding boxes
  final double strokeWidth;

  /// Creates a copy of this [UltralyticsYoloCameraValue] but with
  /// the given fields
  UltralyticsYoloCameraValue copyWith({
    int? lensDirection,
    double? strokeWidth,
  }) =>
      UltralyticsYoloCameraValue(
        lensDirection: lensDirection ?? this.lensDirection,
        strokeWidth: strokeWidth ?? this.strokeWidth,
      );
}

/// ValueNotifier that holds the state of the camera
class UltralyticsYoloCameraController
    extends ValueNotifier<UltralyticsYoloCameraValue> {
  /// Constructor to create an instance of [UltralyticsYoloCameraController]
  UltralyticsYoloCameraController()
      : super(
          UltralyticsYoloCameraValue(
            lensDirection: 1,
            strokeWidth: 2.5,
          ),
        );

  final _ultralyticsYoloPlatform = UltralyticsYoloPlatform.instance;

  /// Toggles the direction of the camera lens
  Future<void> toggleLensDirection() async {
    try {
      // Update state first to show loading state if needed
      final newLensDirection = value.lensDirection == 0 ? 1 : 0;
      value = value.copyWith(lensDirection: newLensDirection);

      // Request camera switch
      final result =
          await _ultralyticsYoloPlatform.setLensDirection(newLensDirection);

      if (result != 'Success') {
        // Revert state if failed
        value = value.copyWith(lensDirection: value.lensDirection == 0 ? 1 : 0);
        throw Exception('Failed to switch camera: $result');
      }
    } catch (e) {
      // Handle errors and revert state
      value = value.copyWith(lensDirection: value.lensDirection == 0 ? 1 : 0);
      rethrow;
    }
  }

  /// Sets the width of the stroke used to draw the bounding boxes
  void setStrokeWidth(double strokeWidth) {
    value = value.copyWith(strokeWidth: strokeWidth);
  }

  /// Closes the camera
  Future<void> closeCamera() async {
    await _ultralyticsYoloPlatform.closeCamera();
  }

  // Start recording
  Future<void> startRecording() async {
    try {
      final result = await _ultralyticsYoloPlatform.startRecording();
      if (result != 'Success') {
        throw Exception('Failed to start recording: $result');
      }
    } catch (e) {
      rethrow;
    }
  }

  // Stop recording
  Future<String> stopRecording() async {
    try {
      final result = await _ultralyticsYoloPlatform.stopRecording();
      if (result == null) {
        throw Exception('Failed to stop recording: null result');
      }

      // Handling the case where the result is "Success: [path]"
      if (result.startsWith('Success')) {
        if (result.length > 8 && result.contains(':')) {
          // Extract the path after "Success: "
          final path = result.substring(result.indexOf(':') + 2);
          if (kDebugMode) {
            print('Recording saved at: $path');
          }
          return path;
        }
        return ''; // Success but no path returned
      } else {
        throw Exception('Failed to stop recording: $result');
      }
    } catch (e) {
      rethrow;
    }
  }

  /// Starts the camera
  Future<void> startCamera() async {
    await _ultralyticsYoloPlatform.startCamera();
  }

  /// Stops the camera
  Future<void> pauseLivePrediction() async {
    await _ultralyticsYoloPlatform.pauseLivePrediction();
  }

  /// Starts saving camera frames to a video file
  Future<String?> saveVideo({String? path}) async {
    try {
      return await _ultralyticsYoloPlatform.saveVideo(path: path);
    } catch (e) {
      rethrow;
    }
  }

  /// Stops saving camera frames and finalizes the video file
  Future<String?> stopSavingVideo() async {
    try {
      return await _ultralyticsYoloPlatform.stopSavingVideo();
    } catch (e) {
      rethrow;
    }
  }

  /// Sets the confidence threshold for object detection
  /// Value should be between 0.0 and 1.0
  Future<void> setConfidenceThreshold(double confidence) async {
    try {
      final result =
          await _ultralyticsYoloPlatform.setConfidenceThreshold(confidence);
      if (result != 'Success') {
        throw Exception('Failed to set confidence threshold: $result');
      }
    } catch (e) {
      rethrow;
    }
  }

  Future<Uint8List?> takePictureAsBytes() async {
    try {
      if (kDebugMode) {
        print('Starting to take picture...');
      }

      final result = await _ultralyticsYoloPlatform.takePictureAsBytes();

      if (result == null) {
        if (kDebugMode) {
          print('Picture taking failed - returned null');
        }
        return null;
      }

      if (kDebugMode) {
        print(
          'Picture taken successfully, size: ${result.lengthInBytes} bytes',
        );
      }

      return result;
    } catch (e) {
      if (kDebugMode) {
        print('Error taking picture: $e');
      }
      return null;
    }
  }
}
