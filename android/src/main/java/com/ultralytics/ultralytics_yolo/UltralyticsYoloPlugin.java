package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

/**
 * UltralyticsYoloPlugin
 */
public class UltralyticsYoloPlugin implements FlutterPlugin, ActivityAware {
    private FlutterPluginBinding flutterPluginBinding;
    private CameraPreview cameraPreview;
    private FrameStreamHandler frameStreamHandler;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        flutterPluginBinding = binding;

        BinaryMessenger binaryMessenger = flutterPluginBinding.getBinaryMessenger();
        Context context = flutterPluginBinding.getApplicationContext();

        cameraPreview = new CameraPreview(context);
        
        // Set up frame stream handler
        frameStreamHandler = new FrameStreamHandler(cameraPreview);
        new EventChannel(binaryMessenger, "ultralytics_yolo_frame_stream")
                .setStreamHandler(frameStreamHandler);

        MethodCallHandler methodCallHandler = new MethodCallHandler(
                binaryMessenger,
                context, cameraPreview);
        new MethodChannel(binaryMessenger, "ultralytics_yolo")
                .setMethodCallHandler(methodCallHandler);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        // Call shutdown to ensure recording properly stops if active
        if (cameraPreview != null) {
            cameraPreview.shutdown();
        }
        flutterPluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        Activity activity = binding.getActivity();
        NativeViewFactory nativeViewFactory = new NativeViewFactory(activity, cameraPreview);
        flutterPluginBinding
                .getPlatformViewRegistry()
                .registerViewFactory("ultralytics_yolo_camera_preview", nativeViewFactory);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {

    }
    
    // Handler for frame streaming events
    static class FrameStreamHandler implements EventChannel.StreamHandler {
        private final CameraPreview cameraPreview;

        FrameStreamHandler(CameraPreview cameraPreview) {
            this.cameraPreview = cameraPreview;
        }

        @Override
        public void onListen(Object arguments, EventChannel.EventSink events) {
            cameraPreview.setFrameStreamSink(events);
        }

        @Override
        public void onCancel(Object arguments) {
            cameraPreview.setFrameStreamSink(null);
            cameraPreview.stopFrameStream();
        }
    }
}