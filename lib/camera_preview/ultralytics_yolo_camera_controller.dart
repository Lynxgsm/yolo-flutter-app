import 'package:flutter/foundation.dart';
import 'package:ultralytics_yolo/ultralytics_yolo_platform_interface.dart';
import 'dart:typed_data';

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
  Future<void> stopRecording() async {
    try {
      final result = await _ultralyticsYoloPlatform.stopRecording();
      if (result != 'Success') {
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

  /// Takes a picture and returns it as bytes (Uint8List)
  /// Returns null if the picture could not be taken
  Future<Uint8List?> takePictureAsBytes() async {
    try {
      return await _ultralyticsYoloPlatform.takePictureAsBytes();
    } catch (e) {
      if (kDebugMode) {
        print('Error taking picture: $e');
      }
      return null;
    }
  }
}
