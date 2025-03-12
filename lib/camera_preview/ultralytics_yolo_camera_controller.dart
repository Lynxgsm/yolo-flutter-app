import 'package:flutter/foundation.dart';
import 'dart:typed_data';
import 'package:ultralytics_yolo/ultralytics_yolo_platform_interface.dart';

/// Callback type for frame listeners
typedef FrameCallback = void Function(FrameData frameData);

/// Class that holds frame data
class FrameData {
  /// Constructor to create an instance of [FrameData]
  FrameData({
    required this.bytes,
    required this.width,
    required this.height,
    required this.format,
    required this.rotation,
  });

  /// Raw bytes of the frame
  final Uint8List bytes;

  /// Width of the frame in pixels
  final int width;

  /// Height of the frame in pixels
  final int height;

  /// Format of the frame (usually RGBA or YUV)
  final String format;

  /// Rotation of the frame in degrees
  final int rotation;
}

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
  final Set<FrameCallback> _frameListeners = {};
  bool _isFrameStreamActive = false;

  @override
  void dispose() {
    stopFrameStream();
    _frameListeners.clear();
    super.dispose();
  }

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
    stopFrameStream();
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

  /// Adds a callback for frame updates
  void addFrameListener(FrameCallback listener) {
    _frameListeners.add(listener);
    if (_frameListeners.isNotEmpty && !_isFrameStreamActive) {
      startFrameStream();
    }
  }

  /// Removes a previously registered frame callback
  void removeFrameListener(FrameCallback listener) {
    _frameListeners.remove(listener);
    if (_frameListeners.isEmpty && _isFrameStreamActive) {
      stopFrameStream();
    }
  }

  /// Starts listening to frame updates from the native platform
  Future<void> startFrameStream() async {
    if (!_isFrameStreamActive) {
      _isFrameStreamActive = true;
      _ultralyticsYoloPlatform.frameStream.listen(_onFrameAvailable);
      await _ultralyticsYoloPlatform.startFrameStream();
    }
  }

  /// Stops listening to frame updates from the native platform
  Future<void> stopFrameStream() async {
    if (_isFrameStreamActive) {
      _isFrameStreamActive = false;
      await _ultralyticsYoloPlatform.stopFrameStream();
    }
  }

  /// Handles incoming frames from the native platform
  void _onFrameAvailable(Map<String, dynamic> frameData) {
    if (_frameListeners.isEmpty) return;

    final frame = FrameData(
      bytes: frameData['bytes'] as Uint8List,
      width: frameData['width'] as int,
      height: frameData['height'] as int,
      format: frameData['format'] as String,
      rotation: frameData['rotation'] as int,
    );

    for (final listener in _frameListeners) {
      try {
        listener(frame);
      } catch (e) {
        print('Error in frame listener: $e');
      }
    }
  }
}
