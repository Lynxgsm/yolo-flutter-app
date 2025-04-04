package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodChannel;

import com.ultralytics.ultralytics_yolo.predict.Predictor;

/**
 * UltralyticsYoloPlugin
 */
public class UltralyticsYoloPlugin implements FlutterPlugin, ActivityAware {
    private FlutterPluginBinding flutterPluginBinding;
    private CameraPreview cameraPreview;
    private MethodCallHandler methodCallHandler;
    private MethodChannel methodChannel;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        flutterPluginBinding = binding;

        BinaryMessenger binaryMessenger = flutterPluginBinding.getBinaryMessenger();
        Context context = flutterPluginBinding.getApplicationContext();

        cameraPreview = new CameraPreview(context);

        methodCallHandler = new MethodCallHandler(
                binaryMessenger,
                context, cameraPreview);
        methodChannel = new MethodChannel(binaryMessenger, "ultralytics_yolo");
        methodChannel.setMethodCallHandler(methodCallHandler);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        // Clean up resources
        if (methodChannel != null) {
            methodChannel.setMethodCallHandler(null);
            methodChannel = null;
        }
        
        if (methodCallHandler != null) {
            methodCallHandler.dispose();
            methodCallHandler = null;
        }
        
        // Shutdown camera preview
        if (cameraPreview != null) {
            cameraPreview.shutdown();
            cameraPreview = null;
        }
        
        // Shutdown predictor thread pools
        Predictor.shutdownExecutors();
        
        android.util.Log.d("UltralyticsYoloPlugin", "All resources cleaned up");
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
        // No resources to clean up specifically for config changes
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        // Clean up any activity-specific resources if needed
    }
}