package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

/**
 * UltralyticsYoloPlugin
 */
public class UltralyticsYoloPlugin implements FlutterPlugin, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
    private FlutterPluginBinding flutterPluginBinding;
    private CameraPreview cameraPreview;
    private ActivityPluginBinding activityPluginBinding;
    private Activity activity;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        flutterPluginBinding = binding;

        BinaryMessenger binaryMessenger = flutterPluginBinding.getBinaryMessenger();
        Context context = flutterPluginBinding.getApplicationContext();

        cameraPreview = new CameraPreview(context);

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
        activity = binding.getActivity();
        activityPluginBinding = binding;
        binding.addRequestPermissionsResultListener(this);
        
        NativeViewFactory nativeViewFactory = new NativeViewFactory(activity, cameraPreview);
        flutterPluginBinding
                .getPlatformViewRegistry()
                .registerViewFactory("ultralytics_yolo_camera_preview", nativeViewFactory);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        if (activityPluginBinding != null) {
            activityPluginBinding.removeRequestPermissionsResultListener(this);
            activityPluginBinding = null;
        }
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        activityPluginBinding = binding;
        binding.addRequestPermissionsResultListener(this);
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityPluginBinding != null) {
            activityPluginBinding.removeRequestPermissionsResultListener(this);
            activityPluginBinding = null;
        }
        activity = null;
    }
    
    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PermissionHelper.PERMISSIONS_REQUEST_CODE) {
            // Check if all permissions were granted
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            android.util.Log.d("UltralyticsYoloPlugin", "Permission request result: " + (allGranted ? "granted" : "denied"));
            
            return true; // We've handled this permission request
        }
        return false; // We haven't handled this permission request
    }
}