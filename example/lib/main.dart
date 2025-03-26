import 'dart:io' as io;
import 'dart:math' show min;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:ultralytics_yolo/ultralytics_yolo.dart';
import 'package:ultralytics_yolo/yolo_model.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final controller = UltralyticsYoloCameraController();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        body: FutureBuilder<bool>(
          future: _checkPermissions(),
          builder: (context, snapshot) {
            final allPermissionsGranted = snapshot.data ?? false;

            return !allPermissionsGranted
                ? const Center(
                    child: Text("Error requesting permissions"),
                  )
                : FutureBuilder<ObjectDetector>(
                    future: _initObjectDetectorWithLocalModel(),
                    builder: (context, snapshot) {
                      final predictor = snapshot.data;

                      return predictor == null
                          ? Container()
                          : Stack(
                              children: [
                                UltralyticsYoloCameraPreview(
                                  controller: controller,
                                  predictor: predictor,
                                  onCameraCreated: () {
                                    predictor.loadModel(useGpu: true);
                                  },
                                ),
                                StreamBuilder<double?>(
                                  stream: predictor.inferenceTime,
                                  builder: (context, snapshot) {
                                    final inferenceTime = snapshot.data;

                                    return StreamBuilder<double?>(
                                      stream: predictor.fpsRate,
                                      builder: (context, snapshot) {
                                        final fpsRate = snapshot.data;

                                        return Times(
                                          inferenceTime: inferenceTime,
                                          fpsRate: fpsRate,
                                        );
                                      },
                                    );
                                  },
                                ),
                              ],
                            );
                    },
                  );
            // : FutureBuilder<ObjectClassifier>(
            //     future: _initObjectClassifierWithLocalModel(),
            //     builder: (context, snapshot) {
            //       final predictor = snapshot.data;

            //       return predictor == null
            //           ? Container()
            //           : Stack(
            //               children: [
            //                 UltralyticsYoloCameraPreview(
            //                   controller: controller,
            //                   predictor: predictor,
            //                   onCameraCreated: () {
            //                     predictor.loadModel();
            //                   },
            //                 ),
            //                 StreamBuilder<double?>(
            //                   stream: predictor.inferenceTime,
            //                   builder: (context, snapshot) {
            //                     final inferenceTime = snapshot.data;

            //                     return StreamBuilder<double?>(
            //                       stream: predictor.fpsRate,
            //                       builder: (context, snapshot) {
            //                         final fpsRate = snapshot.data;

            //                         return Times(
            //                           inferenceTime: inferenceTime,
            //                           fpsRate: fpsRate,
            //                         );
            //                       },
            //                     );
            //                   },
            //                 ),
            //               ],
            //             );
            //     },
            //   );
          },
        ),
        floatingActionButton: Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                FloatingActionButton(
                  heroTag: "saveVideo",
                  child: const Icon(Icons.video_camera_back),
                  onPressed: () async {
                    try {
                      // Start recording using the standard recording API
                      final result = await controller.startRecording();

                      // Show toast or message to indicate recording started
                      ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Recording started')));

                      // Schedule recording to stop after 5 seconds for demo purposes
                      Future.delayed(const Duration(seconds: 5), () async {
                        try {
                          final stopResult = await predictor
                              .ultralyticsYoloPlatform
                              .stopRecording();
                          String message = 'Recording stopped';

                          // Extract the path from the result string if present
                          if (stopResult != null &&
                              stopResult.startsWith('Success: ')) {
                            final videoPath =
                                stopResult.substring('Success: '.length);
                            message = 'Video saved at: $videoPath';

                            // Verify the file exists with extended debugging
                            try {
                              final file = io.File(videoPath);
                              print('Checking file at path: $videoPath');

                              final directory = io.Directory(
                                  io.Directory(videoPath).parent.path);
                              print(
                                  'Directory exists: ${directory.existsSync()}');

                              if (directory.existsSync()) {
                                print('Directory contents:');
                                directory.listSync().forEach((entity) {
                                  print(' - ${entity.path}');
                                  if (entity is io.File) {
                                    try {
                                      final fileSize = entity.lengthSync();
                                      final lastModified =
                                          entity.lastModifiedSync();
                                      print('   Size: $fileSize bytes');
                                      print('   Last modified: $lastModified');
                                      print(
                                          '   Can read: ${entity.existsSync()}');
                                    } catch (e) {
                                      print('   Error getting file info: $e');
                                    }
                                  }
                                });
                              }

                              // Check if file exists and get size
                              if (await file.exists()) {
                                try {
                                  final size = await file.length();
                                  final lastModified =
                                      await file.lastModified();
                                  final stat = await file.stat();

                                  print('File exists with size: $size bytes');
                                  print('Last modified: $lastModified');
                                  print('File stats: $stat');

                                  message =
                                      'Video saved at: $videoPath (${(size / 1024).toStringAsFixed(1)} KB)';

                                  // Try to read the first few bytes to check if file is accessible
                                  try {
                                    final randomAccessFile =
                                        await file.open(mode: io.FileMode.read);
                                    final bytes = await randomAccessFile
                                        .read(min(1024, size.toInt()));
                                    await randomAccessFile.close();
                                    print(
                                        'Successfully read ${bytes.length} bytes from file');
                                  } catch (e) {
                                    print('Error reading file content: $e');
                                  }
                                } catch (e) {
                                  print('Error getting file details: $e');
                                  message =
                                      'File exists but error getting details: $e';
                                }
                              } else {
                                message =
                                    'File path returned but file not found: $videoPath';
                                print('File does not exist at: $videoPath');
                              }
                            } catch (e) {
                              print('Error checking file: $e');
                            }
                          }

                          ScaffoldMessenger.of(context)
                              .showSnackBar(SnackBar(content: Text(message)));
                        } catch (e) {
                          print('Error stopping recording: $e');
                          ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(content: Text('Error stopping: $e')));
                        }
                      });
                    } catch (e) {
                      print('Error starting recording: $e');
                      ScaffoldMessenger.of(context)
                          .showSnackBar(SnackBar(content: Text('Error: $e')));
                    }
                  },
                ),
                const SizedBox(height: 8),
                const Text('Record', style: TextStyle(color: Colors.white)),
              ],
            ),
            const SizedBox(width: 16),
            Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                FloatingActionButton(
                  heroTag: "stopSaveVideo",
                  child: const Icon(Icons.video_collection),
                  onPressed: () async {
                    try {
                      // Get the directory for saving the video
                      final directory =
                          await getApplicationDocumentsDirectory();
                      final path = '${directory.path}/frame_capture_video.mp4';

                      // Start saving video frames
                      final result = await controller.saveVideo(path: path);

                      // Show toast or message to indicate frame capture started
                      String message = 'Started frame capture';
                      if (result != null && result.startsWith('Success: ')) {
                        final videoPath = result.substring('Success: '.length);
                        message =
                            'Frame capture started, will save to: $videoPath';
                      }

                      ScaffoldMessenger.of(context)
                          .showSnackBar(SnackBar(content: Text(message)));

                      // Schedule frame capture to stop after 5 seconds for demo purposes
                      Future.delayed(const Duration(seconds: 5), () async {
                        try {
                          final stopResult = await controller.stopSavingVideo();
                          String stopMessage = 'Frame capture completed';

                          // Extract the path from the result string if present
                          if (stopResult != null &&
                              stopResult.startsWith('Success: ')) {
                            final videoPath =
                                stopResult.substring('Success: '.length);
                            stopMessage =
                                'Frame capture video saved at: $videoPath';

                            // Verify the file exists with extended debugging
                            try {
                              final file = io.File(videoPath);
                              print(
                                  'Checking frame capture file at path: $videoPath');

                              final directory = io.Directory(
                                  io.Directory(videoPath).parent.path);
                              print(
                                  'Directory exists: ${directory.existsSync()}');

                              if (directory.existsSync()) {
                                print('Directory contents:');
                                directory.listSync().forEach((entity) {
                                  print(' - ${entity.path}');
                                  if (entity is io.File) {
                                    try {
                                      final fileSize = entity.lengthSync();
                                      final lastModified =
                                          entity.lastModifiedSync();
                                      print('   Size: $fileSize bytes');
                                      print('   Last modified: $lastModified');
                                      print(
                                          '   Can read: ${entity.existsSync()}');
                                    } catch (e) {
                                      print('   Error getting file info: $e');
                                    }
                                  }
                                });
                              }

                              // Check if file exists and get size
                              if (await file.exists()) {
                                try {
                                  final size = await file.length();
                                  final lastModified =
                                      await file.lastModified();
                                  final stat = await file.stat();

                                  print(
                                      'Frame file exists with size: $size bytes');
                                  print('Last modified: $lastModified');
                                  print('File stats: $stat');

                                  stopMessage =
                                      'Frame video saved at: $videoPath (${(size / 1024).toStringAsFixed(1)} KB)';

                                  // Try to read the first few bytes to check if file is accessible
                                  try {
                                    final randomAccessFile =
                                        await file.open(mode: io.FileMode.read);
                                    final bytes = await randomAccessFile
                                        .read(min(1024, size.toInt()));
                                    await randomAccessFile.close();
                                    print(
                                        'Successfully read ${bytes.length} bytes from frame file');
                                  } catch (e) {
                                    print(
                                        'Error reading frame file content: $e');
                                  }
                                } catch (e) {
                                  print('Error getting frame file details: $e');
                                  stopMessage =
                                      'Frame file exists but error getting details: $e';
                                }
                              } else {
                                stopMessage =
                                    'File path returned but frame file not found: $videoPath';
                                print(
                                    'Frame file does not exist at: $videoPath');
                              }
                            } catch (e) {
                              print('Error checking frame file: $e');
                            }
                          }

                          ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(content: Text(stopMessage)));
                        } catch (e) {
                          print('Error stopping frame capture: $e');
                          ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                              content:
                                  Text('Error stopping frame capture: $e')));
                        }
                      });
                    } catch (e) {
                      print('Error starting frame capture: $e');
                      ScaffoldMessenger.of(context)
                          .showSnackBar(SnackBar(content: Text('Error: $e')));
                    }
                  },
                ),
                const SizedBox(height: 8),
                const Text('Frames', style: TextStyle(color: Colors.white)),
              ],
            ),
            const SizedBox(width: 16),
            Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                FloatingActionButton(
                  heroTag: "switchCamera",
                  child: const Icon(Icons.cameraswitch),
                  onPressed: () {
                    controller.toggleLensDirection();
                  },
                ),
                const SizedBox(height: 8),
                const Text('Switch', style: TextStyle(color: Colors.white)),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<ObjectDetector> _initObjectDetectorWithLocalModel() async {
    // FOR IOS
    final modelPath = await _copy('assets/yolov8n.mlmodel');
    final model = LocalYoloModel(
      id: '',
      task: Task.detect,
      format: Format.coreml,
      modelPath: modelPath,
    );
    // FOR ANDROID
    // final modelPath = await _copy('assets/yolov8n_int8.tflite');
    // final metadataPath = await _copy('assets/metadata.yaml');
    // final model = LocalYoloModel(
    //   id: '',
    //   task: Task.detect,
    //   format: Format.tflite,
    //   modelPath: modelPath,
    //   metadataPath: metadataPath,
    // );

    return ObjectDetector(model: model);
  }

  Future<ImageClassifier> _initImageClassifierWithLocalModel() async {
    final modelPath = await _copy('assets/yolov8n-cls.mlmodel');
    final model = LocalYoloModel(
      id: '',
      task: Task.classify,
      format: Format.coreml,
      modelPath: modelPath,
    );

    // final modelPath = await _copy('assets/yolov8n-cls.bin');
    // final paramPath = await _copy('assets/yolov8n-cls.param');
    // final metadataPath = await _copy('assets/metadata-cls.yaml');
    // final model = LocalYoloModel(
    //   id: '',
    //   task: Task.classify,
    //   modelPath: modelPath,
    //   paramPath: paramPath,
    //   metadataPath: metadataPath,
    // );

    return ImageClassifier(model: model);
  }

  Future<String> _copy(String assetPath) async {
    final path = '${(await getApplicationSupportDirectory()).path}/$assetPath';
    await io.Directory(dirname(path)).create(recursive: true);
    final file = io.File(path);
    if (!await file.exists()) {
      final byteData = await rootBundle.load(assetPath);
      await file.writeAsBytes(byteData.buffer
          .asUint8List(byteData.offsetInBytes, byteData.lengthInBytes));
    }
    return file.path;
  }

  Future<bool> _checkPermissions() async {
    List<Permission> permissions = [];

    var cameraStatus = await Permission.camera.status;
    if (!cameraStatus.isGranted) permissions.add(Permission.camera);

    // var storageStatus = await Permission.photos.status;
    // if (!storageStatus.isGranted) permissions.add(Permission.photos);

    if (permissions.isEmpty) {
      return true;
    } else {
      try {
        Map<Permission, PermissionStatus> statuses =
            await permissions.request();
        return statuses.values
            .every((status) => status == PermissionStatus.granted);
      } on Exception catch (_) {
        return false;
      }
    }
  }
}

class Times extends StatelessWidget {
  const Times({
    super.key,
    required this.inferenceTime,
    required this.fpsRate,
  });

  final double? inferenceTime;
  final double? fpsRate;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Align(
        alignment: Alignment.bottomCenter,
        child: Container(
            margin: const EdgeInsets.all(20),
            padding: const EdgeInsets.all(20),
            decoration: const BoxDecoration(
              borderRadius: BorderRadius.all(Radius.circular(10)),
              color: Colors.black54,
            ),
            child: Text(
              '${(inferenceTime ?? 0).toStringAsFixed(1)} ms  -  ${(fpsRate ?? 0).toStringAsFixed(1)} FPS',
              style: const TextStyle(color: Colors.white70),
            )),
      ),
    );
  }
}
