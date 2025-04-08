enum CameraMode {
  photo,
  video,
}

extension CameraModeExtension on CameraMode {
  String get name => toString().split('.').last;
}
