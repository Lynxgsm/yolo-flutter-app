package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;

import java.util.Map;

import io.flutter.plugin.platform.PlatformView;

class NativeView implements PlatformView {
    private final Activity activity;
    private View view;
    private PreviewView mPreviewView;
    private final CameraPreview cameraPreview;

    NativeView(@NonNull Activity activity, @NonNull Context context, @Nullable Map<String, Object> creationParams, CameraPreview cameraPreview) {
        this.activity = activity;
        this.cameraPreview = cameraPreview;

        final int lensDirection = (int) creationParams.get("lensDirection");
//        final String format = (String) creationParams.get("format");

//        if (Objects.requireNonNull(format).equals("tflite")) {
        view = LayoutInflater.from(context).inflate(R.layout.activity_tflite_camera, null);
        mPreviewView = view.findViewById(R.id.previewView);
        startTfliteCamera(lensDirection);
//        }

    }

    @NonNull
    @Override
    public View getView() {
        return view;
    }

    @Override
    public void dispose() {
    }

    private void startTfliteCamera(int facing) {
        // Check permissions before starting camera
        if (PermissionHelper.checkAndRequestPermissions(activity)) {
            cameraPreview.openCamera(facing, activity, mPreviewView);
        } else {
            // Display message about permissions
            android.util.Log.e("NativeView", "Camera permissions not granted");
            
            // Create a simple text view to show when permissions are missing
            android.widget.TextView errorText = new android.widget.TextView(activity);
            errorText.setText("Camera permission required. Please grant permissions in app settings.");
            errorText.setTextColor(android.graphics.Color.RED);
            errorText.setTextSize(16);
            errorText.setGravity(android.view.Gravity.CENTER);
            errorText.setPadding(20, 20, 20, 20);
            
            // Replace the view with this error message
            if (view instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) view).removeAllViews();
                ((android.view.ViewGroup) view).addView(errorText);
            }
        }
    }
}
