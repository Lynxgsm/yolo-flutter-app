import 'dart:io';

import 'package:stacked/stacked.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:ultralytics_yolo/ultralytics_yolo.dart';
import 'package:ultralytics_yolo_example/screens/take_photo/take_photo_viewmodel.dart';

class TakePhotoView extends StatelessWidget {
  final UltralyticsYoloCameraController controller;

  final bool isDamage;
  const TakePhotoView(
      {super.key, required this.controller, required this.isDamage});

  // Request all necessary permissions
  Future<bool> _requestPermissions(BuildContext context) async {
    if (Platform.isAndroid) {
      Map<Permission, PermissionStatus> statuses = await [
        Permission.camera,
        Permission.microphone,
        Permission.storage,
      ].request();

      // Check each permission
      if (statuses[Permission.camera] != PermissionStatus.granted) {
        _showPermissionError(context, "Camera");
        return false;
      }

      if (statuses[Permission.microphone] != PermissionStatus.granted) {
        _showPermissionError(context, "Microphone");
        return false;
      }

      // if (statuses[Permission.storage] != PermissionStatus.granted) {
      //   _showPermissionError(context, "Storage");
      //   return false;
      // }

      return true;
    }

    // For iOS, assume permissions are handled by Info.plist
    return true;
  }

  void _showPermissionError(BuildContext context, String permissionType) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
            "$permissionType permission denied. Please enable it in app settings."),
        duration: const Duration(seconds: 5),
        action: SnackBarAction(
          label: 'Settings',
          onPressed: () {
            openAppSettings();
          },
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ViewModelBuilder<TakePhotoViewModel>.reactive(
      viewModelBuilder: () => TakePhotoViewModel(),
      onViewModelReady: (model) => model.init(),
      builder: (
        BuildContext context,
        TakePhotoViewModel model,
        Widget? child,
      ) {
        return Scaffold(
          body: FutureBuilder(
            future: _requestPermissions(context),
            builder: (context, permissionSnapshot) {
              if (permissionSnapshot.connectionState ==
                  ConnectionState.waiting) {
                return const Center(child: CircularProgressIndicator());
              }

              // If permissions not granted, show message
              if (permissionSnapshot.data == false) {
                return const Center(
                  child: Text(
                      'Missing required permissions. Please grant permissions in app settings.'),
                );
              }

              // Permissions granted, now load the model
              return FutureBuilder<ObjectDetector>(
                future: model.initObjectDetectorWithLocalModel(),
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }

                  if (snapshot.hasError) {
                    return Center(
                      child: Text('Error loading model: ${snapshot.error}'),
                    );
                  }

                  final predictor = snapshot.data;
                  model.predictor = predictor;

                  return predictor == null
                      ? const Center(child: Text('Failed to initialize model'))
                      : UltralyticsYoloCameraPreview(
                          controller: controller,
                          predictor: predictor,
                          onCameraCreated: () async {
                            model.predictor = predictor;
                            debugPrint("onCameraCreated: $predictor");
                            predictor.loadModel(useGpu: true);
                            predictor.setNumItemsThreshold(10);
                            predictor.setConfidenceThreshold(0.3);
                          },
                        );
                },
              );
            },
          ),
          floatingActionButton: FloatingActionButton(
            onPressed: () {
              model.predictor?.ultralyticsYoloPlatform
                  .takePictureAsBytes()
                  .then((value) {
                if (value != null) {
                  showDialog(
                      context: context,
                      builder: (context) => AlertDialog(
                            title: const Text("Image"),
                            content: Image.memory(value),
                          ));
                }
              });
            },
            child: const Icon(Icons.camera_alt),
          ),
        );
      },
    );
  }
}
