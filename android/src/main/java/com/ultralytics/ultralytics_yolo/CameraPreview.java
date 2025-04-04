package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;
import android.util.Size;
import java.io.File;
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

            // Set up video capture
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);

            // Unbind use cases before rebinding
            cameraProvider.unbindAll();

            // Bind use cases to camera
            Camera camera = cameraProvider.bindToLifecycle(
                    (LifecycleOwner) activity, 
                    cameraSelector, 
                    cameraPreview, 
                    imageAnalysis,
                    videoCapture);

            cameraControl = camera.getCameraControl();

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
                outputDir.mkdirs();
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File outputFile = new File(outputDir, "yolo_recording_" + timestamp + ".mp4");
            
            // Log recording details
            android.util.Log.d("CameraPreview", "Starting recording to " + outputFile.getAbsolutePath());
            
            // Set up recording options with highest quality
            FileOutputOptions fileOutputOptions = new FileOutputOptions.Builder(outputFile).build();
            
            // Start recording
            currentRecording = videoCapture.getOutput().prepareRecording(context, fileOutputOptions)
                    // Enable audio recording if needed
                    .withAudioEnabled()
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
            
            // Use a thread to avoid blocking the UI
            new Thread(() -> {
                try {
                    recording.stop();
                    android.util.Log.d("CameraPreview", "Recording stopped successfully");
                } catch (Exception e) {
                    android.util.Log.e("CameraPreview", "Error stopping recording: " + e.getMessage());
                    e.printStackTrace();
                }
            }).start();
            
            // Get the file path from the output directory - this is our best guess if we can't get it from events
            File outputDir = new File(context.getExternalFilesDir(null), "recordings");
            File[] files = outputDir.listFiles();
            if (files != null && files.length > 0) {
                // Sort by last modified to get the most recent one
                java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                String path = files[0].getAbsolutePath();
                android.util.Log.d("CameraPreview", "Most recent recording file: " + path);
                return "Success: " + path;
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
}