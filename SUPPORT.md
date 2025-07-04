# Supported Devices and Platforms

This project supports running Ultralytics YOLO models on both Android and iOS devices via Flutter.

## Platforms

| Platform | Supported | Minimum OS Version   | Recommended Hardware                                                        | Notes                                                                                                                          |
| -------- | --------- | -------------------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Android  | ✅        | Android 5.0 (API 26) | Modern devices with Snapdragon, Exynos, Dimensity, Kirin, or Tegra chipsets | Uses TensorFlow Lite. Supports CPU, GPU, Hexagon, and NNAPI delegates. Real-time performance (up to 30 FPS) on recent devices. |
| iOS      | ✅        | iOS 13.0             | iPhone/iPad with A11 Bionic (iPhone X, 2017) or newer                       | Uses CoreML and Apple Neural Engine (ANE) for acceleration. Real-time performance (up to 30 FPS) on recent devices.            |

## Android Details

- **Supported chipsets:** Qualcomm Snapdragon, Samsung Exynos, MediaTek Dimensity, HiSilicon Kirin, NVIDIA Tegra
- **Delegates:** CPU, GPU, Hexagon (Snapdragon), NNAPI
- **Popular tested devices:**
  - Samsung Galaxy S21, S22, S23
  - Google Pixel 4, 5, 6, 7
  - OnePlus 9, 10
  - Xiaomi Redmi Note series
  - Huawei P40 Pro, Mate 30 Pro
  - NVIDIA Shield TV
- **Performance:** Up to 30 FPS with INT8 quantized models on modern hardware
- **Model format:** `.tflite` (TensorFlow Lite)

## iOS Details

- **Supported devices:** iPhone X (A11 Bionic, 2017) and newer, iPad Pro (2018+) and newer
- **Apple Neural Engine (ANE):** Used for hardware acceleration
- **Popular tested devices:**
  - iPhone X, XS, 11, 12, 13, 14, 15
  - iPad Pro (2018+)
- **Performance:** Up to 30 FPS with FP16/INT8 quantized models on modern hardware
- **Model format:** `.mlmodel` (CoreML)

## General Notes

- **Camera:** Requires a device with a working camera (front or back)
- **Permissions:**
  - Android: Camera, Storage
  - iOS: Camera, Photo Library
- **Model requirements:** Only models exported with the official Ultralytics export commands are supported (see README for details).
- **Testing:** Always test your app on your target devices for compatibility and performance.

## Not Supported

- Desktop (macOS, Windows, Linux)
- Web
- Devices running OS versions below the minimum listed above

For more information, see the [README.md](README.md) and [official documentation](https://github.com/ultralytics/yolo-flutter-app).
