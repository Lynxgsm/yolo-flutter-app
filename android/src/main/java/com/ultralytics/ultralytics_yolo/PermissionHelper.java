package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for managing permissions across Android versions
 */
public class PermissionHelper {
    
    // Permission request code
    public static final int PERMISSIONS_REQUEST_CODE = 1001;
    
    /**
     * Check and request necessary permissions for camera and storage
     */
    public static boolean checkAndRequestPermissions(Activity activity) {
        List<String> permissionsNeeded = new ArrayList<>();
        
        // Camera is needed for all versions
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(android.Manifest.permission.CAMERA);
        }
        
        // Storage permissions depend on Android version
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 (Pie) and below
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            // Android 10-12 (Q, R, S)
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        } else {
            // Android 13+ (Tiramisu and above)
            // Check for the new media permissions
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.READ_MEDIA_IMAGES) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.READ_MEDIA_VIDEO) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.READ_MEDIA_VIDEO);
            }
        }
        
        // Request permissions if needed
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(activity, 
                    permissionsNeeded.toArray(new String[0]), 
                    PERMISSIONS_REQUEST_CODE);
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if all permissions are granted
     */
    public static boolean areAllPermissionsGranted(Context context) {
        boolean allPermissionsGranted = true;
        
        // Check camera permission
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            allPermissionsGranted = false;
        }
        
        // Check audio permission
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            allPermissionsGranted = false;
        }
        
        // Check storage permissions based on Android version
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 (Pie) and below
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            // Android 10-12 (Q, R, S)
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
            }
        } else {
            // Android 13+ (Tiramisu and above)
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) 
                    != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) 
                    != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
            }
        }
        
        return allPermissionsGranted;
    }
} 