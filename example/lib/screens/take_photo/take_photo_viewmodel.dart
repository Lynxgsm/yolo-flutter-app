import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:stacked/stacked.dart';
import 'package:ultralytics_yolo/ultralytics_yolo.dart';
import 'package:ultralytics_yolo/yolo_model.dart';
import 'package:path/path.dart';
import 'package:ultralytics_yolo_example/enums/camera_mode.dart';

class TakePhotoViewModel extends BaseViewModel {
  List<DetectedObject?> _detections = [];
  Predictor? _predictor;
  bool _isListeningToStream = false;
  bool _isRecording = false;
  bool _exporting = false;
  List<DetectedObject?> get detections => _detections;

  int _detectionCount = 0;
  String? _recordedVideoPath;

  String? get recordedVideoPath => _recordedVideoPath;
  CameraMode _cameraMode = CameraMode.photo;
  CameraMode get cameraMode => _cameraMode;
  Predictor? get predictor => _predictor;
  bool get isRecording => _isRecording;
  bool get exporting => _exporting;
  set cameraMode(CameraMode value) {
    _cameraMode = value;
    notifyListeners();
  }

  set predictor(Predictor? value) {
    _predictor = value;
    Future.microtask(() => notifyListeners());
  }

  int get detectionCount => _detectionCount;

  void addDetections(DetectedObject? value) {
    _detections.add(value);
    Future.microtask(() => notifyListeners());
  }

  Future<String> ensureOutputDirectoryExists(String outputPath) async {
    try {
      // Extract the directory path from the full file path
      final directory = Directory(dirname(outputPath));

      // Create the directory if it doesn't exist
      if (!await directory.exists()) {
        await directory.create(recursive: true);
        debugPrint("Created output directory: ${directory.path}");
      }

      return outputPath;
    } catch (e) {
      debugPrint("Error ensuring output directory exists: $e");
      // Fallback to a directory we know should be writable
      final appDir = await getApplicationDocumentsDirectory();
      final fallbackPath = '${appDir.path}/recorded_video.mp4';
      debugPrint("Using fallback output path: $fallbackPath");
      return fallbackPath;
    }
  }

  Future<void> onRecordingComplete(String videoPath) async {
    debugPrint("Recording completed: $videoPath");

    // Verify the file actually exists
    final file = File(videoPath);
    if (await file.exists()) {
      final fileSize = await file.length();
      debugPrint("Video file exists with size: ${fileSize / 1024 / 1024} MB");
      _recordedVideoPath = videoPath;
    } else {
      debugPrint(
          "WARNING: Recording marked as complete but file does not exist at: $videoPath");
      // Try to find the file in common locations
      await _searchForVideoFile(videoPath);
    }

    _isRecording = false;
    _exporting = false;
    notifyListeners();
  }

  Future<void> _searchForVideoFile(String originalPath) async {
    // Try to find the file by name in common directories
    final filename = basename(originalPath);

    final List<Directory> searchDirs = [];
    try {
      searchDirs.add(await getApplicationDocumentsDirectory());
      searchDirs.add(await getTemporaryDirectory());

      if (Platform.isIOS) {
        // iOS specific directories
        final appSupportDir = await getApplicationSupportDirectory();
        searchDirs.add(appSupportDir);
      } else if (Platform.isAndroid) {
        // Android specific directories
        final externalDir = await getExternalStorageDirectory();
        if (externalDir != null) searchDirs.add(externalDir);
      }
    } catch (e) {
      debugPrint("Error getting search directories: $e");
    }

    // Search in each directory
    for (final dir in searchDirs) {
      try {
        debugPrint("Searching for video in directory: ${dir.path}");
        final files = dir.listSync(recursive: true);
        for (final entity in files) {
          if (entity is File && entity.path.contains(filename)) {
            debugPrint("FOUND VIDEO FILE at: ${entity.path}");
            _recordedVideoPath = entity.path;
            return;
          }
        }
      } catch (e) {
        debugPrint("Error searching directory ${dir.path}: $e");
      }
    }

    debugPrint("Could not find the video file in any common locations");
  }

  Future<void> stopRecording(String path, BuildContext context) async {
    debugPrint("========= Stopping recording =========");

    try {
      _exporting = true;
      _isRecording = false;
      notifyListeners();

      debugPrint("========= Video saved at: $path =========");
      await Future.delayed(const Duration(milliseconds: 1000));

      _exporting = false;
      notifyListeners();
    } catch (e) {
      debugPrint('Error exporting GIF: $e');
      _exporting = false;
      notifyListeners();
      // You might want to show an error message to the user here
    }
  }

  Future<void> init() async {
    try {
      _isRecording = true;
      notifyListeners();
    } catch (e) {
      debugPrint("Error initializing recording controller: $e");
      _isRecording = false;
      notifyListeners();
    }
  }

  void toggleRecording(bool isRecording) {
    _isRecording = isRecording;
    notifyListeners();
  }

  void setExporting(bool value) {
    _exporting = value;
    notifyListeners();
  }

  void clearDetections() {
    _detections = [];
  }

  set detectionCount(int value) {
    _detectionCount = value;
    notifyListeners();
  }

  void getPredictor() {
    if (_predictor != null && !_isListeningToStream) {
      _isListeningToStream = true;
      _predictor?.ultralyticsYoloPlatform.detectionResultStream
          .listen((results) {
        if (results != null) {
          _detectionCount = results.length;
          _detections = results;
          Future.microtask(() => notifyListeners());
        }
      });
    } else {
      debugPrint("Predictor is null or already listening to stream");
    }
  }

  Future<String> copy(String assetPath) async {
    final path = '${(await getApplicationSupportDirectory()).path}/$assetPath';
    await Directory(dirname(path)).create(recursive: true);
    final file = File(path);
    if (!await file.exists()) {
      final byteData = await rootBundle.load(assetPath);
      await file.writeAsBytes(byteData.buffer
          .asUint8List(byteData.offsetInBytes, byteData.lengthInBytes));
    }
    return file.path;
  }

  Future<ObjectDetector> initObjectDetectorWithLocalModel(
      {bool? isDamage}) async {
    final modelName = isDamage == null
        ? 'assets/model/elements.mlmodel'
        : 'assets/model/damage.mlmodel';

    if (Platform.isIOS) {
      final modelPath = await copy(modelName);
      final model = LocalYoloModel(
        id: '',
        task: Task.detect,
        format: Format.coreml,
        modelPath: modelPath,
      );
      return ObjectDetector(model: model);
    } else {
      final modelPath = await copy('assets/yolov8n_int8.tflite');
      final metadataPath = await copy('assets/metadata.yaml');
      final model = LocalYoloModel(
        id: '',
        task: Task.detect,
        format: Format.tflite,
        modelPath: modelPath,
        metadataPath: metadataPath,
      );
      return ObjectDetector(model: model);
    }
  }

  void resetRecordedVideo() {
    _recordedVideoPath = null;
    notifyListeners();
  }

  // Check if video file exists and get details
  Map<String, dynamic> getVideoFileDetails(String path) {
    try {
      final file = File(path);
      final exists = file.existsSync();
      final size = exists ? file.lengthSync() : 0;
      final formattedSize =
          exists ? "${(size / 1024 / 1024).toStringAsFixed(2)} MB" : "Unknown";
      final lastModified = exists ? file.lastModifiedSync() : null;

      return {
        'exists': exists,
        'size': size,
        'formattedSize': formattedSize,
        'lastModified': lastModified?.toString(),
        'path': path,
      };
    } catch (e) {
      debugPrint("Error getting file details: $e");
      return {
        'exists': false,
        'error': e.toString(),
        'path': path,
      };
    }
  }

  // Create a dummy MP4 file to test if we can write video files
  Future<String> createDummyVideoFile() async {
    try {
      final appDir = await getApplicationDocumentsDirectory();
      final videoFilePath = '${appDir.path}/dummy_video.mp4';
      final file = File(videoFilePath);

      // Create a simple header for an empty MP4 file
      // This is a very minimal MP4 header that's valid but won't play
      final List<int> mp4Header = [
        0x00,
        0x00,
        0x00,
        0x20,
        0x66,
        0x74,
        0x79,
        0x70,
        0x6D,
        0x70,
        0x34,
        0x32,
        0x00,
        0x00,
        0x00,
        0x00,
        0x6D,
        0x70,
        0x34,
        0x32,
        0x69,
        0x73,
        0x6F,
        0x6D,
        0x00,
        0x00,
        0x00,
        0x08,
        0x6D,
        0x6F,
        0x6F,
        0x76
      ];

      await file.writeAsBytes(mp4Header);

      debugPrint("Successfully created dummy MP4 file at: $videoFilePath");
      return videoFilePath;
    } catch (e) {
      debugPrint("Error creating dummy MP4 file: $e");
      return "Error: $e";
    }
  }
}
