package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.video.Recorder;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.ultralytics.ultralytics_yolo.predict.Predictor;

import java.util.concurrent.ExecutionException;


public class CameraPreview {
    public final static Size CAMERA_PREVIEW_SIZE = new Size(640, 480);
    private final Context context;
    private Predictor predictor;
    private ProcessCameraProvider cameraProvider;
    private CameraControl cameraControl;
    private Activity activity;
    private PreviewView mPreviewView;
    private boolean busy = false;
    private VideoCapture<Recorder> videoCapture;
    private Recording currentRecording;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;

    public CameraPreview(Context context) {
        this.context = context;
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    public void openCamera(int facing, Activity activity, PreviewView mPreviewView) {
        this.activity = activity;
        this.mPreviewView = mPreviewView;

        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreview(facing);
            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void bindPreview(int facing) {
        if (!busy) {
            busy = true;

            Preview cameraPreview = new Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build();

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(facing)
                    .build();

            ImageAnalysis imageAnalysis =
                    new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .build();
            imageAnalysis.setAnalyzer(Runnable::run, imageProxy -> {
                predictor.predict(imageProxy, facing == CameraSelector.LENS_FACING_FRONT);

                //clear stream for next image
                imageProxy.close();
            });
            
            // Set up image capture
            imageCapture = new ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build();

            // Set up video capture with updated approach for Android 15 compatibility
            try {
                QualitySelector qualitySelector = QualitySelector.from(Quality.HIGHEST);
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(qualitySelector)
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                
                // Unbind use cases before rebinding
                cameraProvider.unbindAll();
                
                // Bind use cases to camera - handle potential exceptions
                try {
                    Camera camera = cameraProvider.bindToLifecycle(
                            (LifecycleOwner) activity, 
                            cameraSelector, 
                            cameraPreview, 
                            imageAnalysis,
                            imageCapture,
                            videoCapture);
                    
                    cameraControl = camera.getCameraControl();
                } catch (Exception e) {
                    android.util.Log.e("CameraPreview", "Error binding use cases: " + e.getMessage());
                    e.printStackTrace();
                    
    
                    try {
                        Camera camera = cameraProvider.bindToLifecycle(
                                (LifecycleOwner) activity, 
                                cameraSelector, 
                                cameraPreview, 
                                imageAnalysis,
                                imageCapture);
                        
                        cameraControl = camera.getCameraControl();
                        videoCapture = null; // Mark videoCapture as unavailable
                    } catch (Exception e2) {
                        android.util.Log.e("CameraPreview", "Error binding without video: " + e2.getMessage());
                        
                        // Final fallback without image and video capture
                        Camera camera = cameraProvider.bindToLifecycle(
                                (LifecycleOwner) activity, 
                                cameraSelector, 
                                cameraPreview, 
                                imageAnalysis);
                        
                        cameraControl = camera.getCameraControl();
                        imageCapture = null; // Mark imageCapture as unavailable
                        videoCapture = null; // Mark videoCapture as unavailable
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("CameraPreview", "Video capture setup error: " + e.getMessage());
                e.printStackTrace();
                
                // Fallback to binding without video capture
                cameraProvider.unbindAll();
                try {
                    Camera camera = cameraProvider.bindToLifecycle(
                            (LifecycleOwner) activity, 
                            cameraSelector, 
                            cameraPreview, 
                            imageAnalysis,
                            imageCapture);
                    
                    cameraControl = camera.getCameraControl();
                } catch (Exception e2) {
                    android.util.Log.e("CameraPreview", "Error binding without video: " + e2.getMessage());
                    
                    // Final fallback without image capture
                    Camera camera = cameraProvider.bindToLifecycle(
                            (LifecycleOwner) activity, 
                            cameraSelector, 
                            cameraPreview, 
                            imageAnalysis);
                    
                    cameraControl = camera.getCameraControl();
                    imageCapture = null; // Mark imageCapture as unavailable
                }
            }

            cameraPreview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

            busy = false;
        }
    }

    public void setPredictorFrameProcessor(Predictor predictor) {
        this.predictor = predictor;
    }

    public void setCameraFacing(int facing) {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            bindPreview(facing);
        }
    }

    public void setScaleFactor(double factor) {
        cameraControl.setZoomRatio((float)factor);
    }
    
    public String startRecording() {
        if (videoCapture == null) {
            return "Error: Camera not initialized";
        }
        
        if (currentRecording != null) {
            return "Error: Recording already in progress";
        }
        
        try {
            // Create output file in Documents directory for better accessibility
            File outputDir = new File(context.getExternalFilesDir(null), "recordings");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    android.util.Log.e("CameraPreview", "Failed to create output directory");
                    return "Error: Failed to create output directory";
                }
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File outputFile = new File(outputDir, "yolo_recording_" + timestamp + ".mp4");
            
            // Log recording details
            android.util.Log.d("CameraPreview", "Starting recording to " + outputFile.getAbsolutePath());
            
            // Set up recording options with highest quality
            FileOutputOptions fileOutputOptions = new FileOutputOptions.Builder(outputFile).build();
            
            // Make sure the output directory is writable
            if (!outputDir.canWrite()) {
                android.util.Log.e("CameraPreview", "Output directory is not writable");
                // Try to create a test file to check write permissions
                try {
                    File testFile = File.createTempFile("test", ".tmp", outputDir);
                    if (testFile.exists()) {
                        testFile.delete();
                        android.util.Log.d("CameraPreview", "Directory passed write test");
                    }
                } catch (Exception e) {
                    android.util.Log.e("CameraPreview", "Write test failed: " + e.getMessage());
                    // Try a different directory if the app-specific directory fails
                    outputDir = context.getFilesDir();
                    outputFile = new File(outputDir, "yolo_recording_" + timestamp + ".mp4");
                    fileOutputOptions = new FileOutputOptions.Builder(outputFile).build();
                    android.util.Log.d("CameraPreview", "Using internal storage instead: " + outputFile.getAbsolutePath());
                }
            }
            
            // Start recording with more detailed error handling
            try {
                currentRecording = videoCapture.getOutput()
                        .prepareRecording(context, fileOutputOptions)
                        .start(ContextCompat.getMainExecutor(context), videoRecordEvent -> {
                            if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                                android.util.Log.d("CameraPreview", "Recording started");
                            } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                                VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                                if (!finalizeEvent.hasError()) {
                                    android.util.Log.d("CameraPreview", "Recording saved successfully to " + 
                                                    finalizeEvent.getOutputResults().getOutputUri());
                                } else {
                                    android.util.Log.e("CameraPreview", "Recording error: " + 
                                                    finalizeEvent.getError());
                                }
                            }
                        });
                
                return "Success";
            } catch (Exception e) {
                android.util.Log.e("CameraPreview", "Exception during recording start: " + e.getMessage());
                e.printStackTrace();
                
                // Try fallback approach for Android 15 if the standard approach fails
                try {
                    // For Android 15, we might need to use MediaStore approach instead of direct file
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        android.util.Log.d("CameraPreview", "Trying MediaStore approach for Android 13+");
                        
                        // Create MediaStore options with ContentValues
                        String name = "yolo_recording_" + timestamp;
                        android.content.ContentValues contentValues = new android.content.ContentValues();
                        contentValues.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name);
                        contentValues.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                        // For Android 10 and above, we can add the relative path
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            contentValues.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, 
                                            "Movies/YoloRecordings");
                        }
                        
                        MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions.Builder(
                                context.getContentResolver(),
                                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                                .setContentValues(contentValues)
                                .build();
                        
                        currentRecording = videoCapture.getOutput()
                                .prepareRecording(context, mediaStoreOutputOptions)
                                .start(ContextCompat.getMainExecutor(context), videoRecordEvent -> {
                                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                                        android.util.Log.d("CameraPreview", "Recording started (MediaStore)");
                                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                                        if (!finalizeEvent.hasError()) {
                                            android.util.Log.d("CameraPreview", "Recording saved successfully (MediaStore) to " + 
                                                            finalizeEvent.getOutputResults().getOutputUri());
                                        } else {
                                            android.util.Log.e("CameraPreview", "Recording error (MediaStore): " + 
                                                            finalizeEvent.getError());
                                        }
                                    }
                                });
                        return "Success";
                    }
                } catch (Exception ex) {
                    android.util.Log.e("CameraPreview", "MediaStore approach also failed: " + ex.getMessage());
                    ex.printStackTrace();
                }
                
                return "Error: " + e.getMessage();
            }
        } catch (Exception e) {
            e.printStackTrace();
            android.util.Log.e("CameraPreview", "Failed to start recording: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    public String stopRecording() {
        if (currentRecording == null) {
            return "Error: No active recording";
        }
        
        try {
            android.util.Log.d("CameraPreview", "Stopping recording");
            Recording recording = currentRecording;
            currentRecording = null;
            
            // Create a reference to last recording path
            final String[] recordingPath = new String[1];
            recordingPath[0] = null;
            
            // CountDownLatch to make sure we wait for recording to stop
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            
            // Store any errors that occur
            final StringBuilder errorMessage = new StringBuilder();
            
            // Use a thread to avoid blocking the UI
            new Thread(() -> {
                try {
                    // Stop the recording
                    recording.stop();
                    android.util.Log.d("CameraPreview", "Recording stopped successfully");
                    
                    // Wait a moment for file to be finalized
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    // Get the file path from the output directory
                    File outputDir = new File(context.getExternalFilesDir(null), "recordings");
                    File[] files = outputDir.listFiles();
                    if (files != null && files.length > 0) {
                        // Sort by last modified to get the most recent one
                        java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                        recordingPath[0] = files[0].getAbsolutePath();
                        android.util.Log.d("CameraPreview", "Found recording file: " + recordingPath[0]);
                    } else {
                        android.util.Log.d("CameraPreview", "No recording files found in " + outputDir.getAbsolutePath());
                    }
                } catch (Exception e) {
                    errorMessage.append(e.getMessage());
                    android.util.Log.e("CameraPreview", "Error stopping recording: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
            
            // Wait for the recording to stop (max 3 seconds)
            try {
                boolean completed = latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                if (!completed) {
                    android.util.Log.w("CameraPreview", "Timed out waiting for recording to stop");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                android.util.Log.e("CameraPreview", "Interrupted while waiting for recording to stop");
            }
            
            // Check if we have an error
            if (errorMessage.length() > 0) {
                return "Error: " + errorMessage.toString();
            }
            
            // Return the path if we found it
            if (recordingPath[0] != null) {
                return "Success: " + recordingPath[0];
            }
            
            return "Success";
        } catch (Exception e) {
            e.printStackTrace();
            android.util.Log.e("CameraPreview", "Error in stopRecording: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    public void saveVideoFrames(String outputPath) {
        // Not implementing frame-by-frame recording for Android currently
        // This is a placeholder for potential future implementation
        android.util.Log.d("CameraPreview", "saveVideoFrames not implemented for Android");
    }
    
    public void shutdown() {
        if (currentRecording != null) {
            try {
                currentRecording.stop();
            } catch (Exception e) {
                android.util.Log.e("CameraPreview", "Error stopping recording during shutdown: " + e.getMessage());
            }
            currentRecording = null;
        }
        
        cameraExecutor.shutdown();
    }

    // Takes a picture and returns the image as bytes
    public interface PhotoCaptureCallback {
        void onCaptureSuccess(byte[] imageBytes);
        void onError(String errorMessage);
    }
    
    public void takePictureAsBytes(PhotoCaptureCallback callback) {
        if (imageCapture == null) {
            callback.onError("Camera photo capture not available on this device");
            return;
        }
        
        // Log that we're taking a picture
        android.util.Log.d("CameraPreview", "Taking picture...");
        
        imageCapture.takePicture(
                cameraExecutor,
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(ImageProxy imageProxy) {
                        try {
                            // Convert ImageProxy to byte array
                            byte[] bytes = imageProxyToJpegByteArray(imageProxy, 100);
                            android.util.Log.d("CameraPreview", "Photo captured successfully, size: " + bytes.length + " bytes");
                            
                            // Return the bytes via callback on main thread
                            ContextCompat.getMainExecutor(context).execute(() -> {
                                callback.onCaptureSuccess(bytes);
                            });
                        } catch (Exception e) {
                            android.util.Log.e("CameraPreview", "Error processing captured image: " + e.getMessage());
                            ContextCompat.getMainExecutor(context).execute(() -> {
                                callback.onError("Error processing image: " + e.getMessage());
                            });
                        } finally {
                            imageProxy.close();
                        }
                    }
                    
                    @Override
                    public void onError(ImageCaptureException exception) {
                        android.util.Log.e("CameraPreview", "Photo capture failed: " + exception.getMessage());
                        ContextCompat.getMainExecutor(context).execute(() -> {
                            callback.onError("Photo capture failed: " + exception.getMessage());
                        });
                    }
                }
        );
    }
    
    // Helper method to convert ImageProxy to JPEG byte array
    private byte[] imageProxyToJpegByteArray(ImageProxy image, int quality) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        
        // Get the image format
        int format = image.getFormat();
        android.util.Log.d("CameraPreview", "Image format: " + format);
        
        // If this is already JPEG, return the bytes directly
        if (format == android.graphics.ImageFormat.JPEG) {
            return bytes;
        }
        
        // Otherwise, we need to convert to JPEG
        Bitmap bitmap = null;
        
        // Convert to bitmap (for most formats like YUV, this is a standard approach)
        try {
            bitmap = ImageUtils.toBitmap(image);
            
            // Apply rotation if needed
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            if (rotationDegrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationDegrees);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            
            // Convert to JPEG bytes
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            android.util.Log.e("CameraPreview", "Error converting image: " + e.getMessage());
            throw new RuntimeException("Failed to convert image to JPEG", e);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }
}