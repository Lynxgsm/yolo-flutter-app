package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;

import android.media.Image;

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
    
    // Interface for receiving the captured image data
    public interface PictureCallback {
        void onPictureTaken(byte[] imageBytes);
        void onError(String errorMessage);
    }
    
    public void takePictureAsBytes(PictureCallback callback) {
        if (cameraProvider == null || activity == null) {
            callback.onError("Camera not initialized");
            return;
        }

        try {
            // Set up image capture
            androidx.camera.core.ImageCapture imageCapture = new androidx.camera.core.ImageCapture.Builder()
                    .setTargetRotation(activity.getWindowManager().getDefaultDisplay().getRotation())
                    .build();

            // Temporarily unbind and rebind with image capture
            cameraProvider.unbindAll();
            
            // Get current camera facing from the preview
            int currentFacing = CameraSelector.LENS_FACING_BACK; // Default to back camera
            if (mPreviewView != null) {
                // Try to get the current camera facing from the preview
                CameraSelector currentSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();
                try {
                    Camera camera = cameraProvider.bindToLifecycle(
                            (LifecycleOwner) activity,
                            currentSelector,
                            new Preview.Builder().build());
                    currentFacing = camera.getCameraInfo().getSensorRotationDegrees() == 0 ?
                            CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
                    cameraProvider.unbindAll();
                } catch (Exception e) {
                    // If we can't determine the current facing, default to back camera
                }
            }
            
            // Use the current camera facing
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(currentFacing)
                    .build();
                    
            // Rebind with image capture
            Preview cameraPreview = new Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build();
                    
            cameraPreview.setSurfaceProvider(mPreviewView.getSurfaceProvider());
                    
            cameraProvider.bindToLifecycle(
                    (LifecycleOwner) activity, 
                    cameraSelector, 
                    cameraPreview,
                    imageCapture);

            // Take the picture to memory
            imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    new androidx.camera.core.ImageCapture.OnImageCapturedCallback() {
                        @Override
                        public void onCaptureSuccess(@NonNull androidx.camera.core.ImageProxy image) {
                            // Convert to bytes
                            byte[] bytes = imageToByteArray(image);
                            image.close(); // Close the image after extraction
                            
                            // Restore camera setup
                            cameraProvider.unbindAll();
                            bindPreview(currentFacing);  // Restore with current camera facing
                            
                            // Return the bytes
                            callback.onPictureTaken(bytes);
                        }

                        @Override
                        public void onError(@NonNull androidx.camera.core.ImageCaptureException exception) {
                            // Restore camera setup on error
                            cameraProvider.unbindAll();
                            bindPreview(currentFacing);  // Restore with current camera facing
                            
                            callback.onError(exception.getMessage());
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
            callback.onError(e.getMessage());
        }
    }
    
    private byte[] imageToByteArray(androidx.camera.core.ImageProxy image) {
        // Get the image format
        int format = image.getFormat();
        
        // Handle different image formats
        if (format == ImageFormat.YUV_420_888) {
            // YUV420 format
            androidx.camera.core.ImageProxy.PlaneProxy[] planes = image.getPlanes();
            androidx.camera.core.ImageProxy.PlaneProxy yPlane = planes[0];
            androidx.camera.core.ImageProxy.PlaneProxy uPlane = planes[1];
            androidx.camera.core.ImageProxy.PlaneProxy vPlane = planes[2];

            ByteBuffer yBuffer = yPlane.getBuffer();
            ByteBuffer uBuffer = uPlane.getBuffer();
            ByteBuffer vBuffer = vPlane.getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];

            // U and V are swapped
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            // Convert NV21 to JPEG
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);

            return out.toByteArray();
        } else if (format == ImageFormat.JPEG) {
            // JPEG format
            androidx.camera.core.ImageProxy.PlaneProxy[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } else {
            // Unsupported format
            throw new IllegalArgumentException("Unsupported image format: " + format);
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
