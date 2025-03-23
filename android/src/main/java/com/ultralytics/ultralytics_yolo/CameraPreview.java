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

import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.annotation.NonNull;

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
    
    public interface PhotoCaptureCallback {
        void onPhotoCaptured(String filePath);
        void onError(String errorMessage);
    }
    
    public void takePhoto(PhotoCaptureCallback callback) {
        if (cameraProvider == null) {
            callback.onError("Camera not initialized");
            return;
        }
        
        try {
            // Create output directory
            File outputDir = new File(context.getCacheDir(), "photos");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            // Create output file with timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File outputFile = new File(outputDir, "yolo_photo_" + timestamp + ".jpg");
            
            // Create image capture use case
            ImageCapture imageCapture = new ImageCapture.Builder()
                    .setTargetRotation(((Activity) context).getWindowManager().getDefaultDisplay().getRotation())
                    .build();
            
            // Unbind video capture uses cases and bind image capture
            Camera camera = null;
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();
                
                // Rebind preview and image capture
                Preview cameraPreview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build();
                cameraPreview.setSurfaceProvider(mPreviewView.getSurfaceProvider());
                
                camera = cameraProvider.bindToLifecycle(
                        (LifecycleOwner) activity, 
                        cameraSelector, 
                        cameraPreview, 
                        imageCapture);
                
                // Capture the image
                ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(outputFile).build();
                
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            // Photo saved successfully, rebind original use cases
                            callback.onPhotoCaptured(outputFile.getAbsolutePath());
                            // Rebind original use cases
                            bindPreview(cameraSelector.getLensFacing());
                        }
                        
                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            callback.onError("Error capturing photo: " + exception.getMessage());
                            // Rebind original use cases
                            bindPreview(cameraSelector.getLensFacing());
                        }
                    }
                );
            }
        } catch (Exception e) {
            callback.onError("Error setting up photo capture: " + e.getMessage());
        }
    }
    
    public String startRecording() {
        if (videoCapture == null) {
            return "Error: Camera not initialized";
        }
        
        if (currentRecording != null) {
            return "Error: Recording already in progress";
        }
        
        try {
            // Create output file
            File outputDir = new File(context.getCacheDir(), "recordings");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File outputFile = new File(outputDir, "yolo_recording_" + timestamp + ".mp4");
            
            // Set up recording options
            FileOutputOptions fileOutputOptions = new FileOutputOptions.Builder(outputFile).build();
            
            // Start recording
            currentRecording = videoCapture.getOutput().prepareRecording(context, fileOutputOptions)
                    .start(ContextCompat.getMainExecutor(context), videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                            if (!finalizeEvent.hasError()) {
                                // Recording saved successfully
                            } else {
                                // Handle recording error
                            }
                        }
                    });
            
            return "Success";
        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }
    
    public String stopRecording() {
        if (currentRecording == null) {
            return "Error: No active recording";
        }
        
        try {
            currentRecording.stop();
            currentRecording = null;
            return "Success";
        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }
    
    public void shutdown() {
        if (currentRecording != null) {
            currentRecording.stop();
            currentRecording = null;
        }
        
        cameraExecutor.shutdown();
    }
}
