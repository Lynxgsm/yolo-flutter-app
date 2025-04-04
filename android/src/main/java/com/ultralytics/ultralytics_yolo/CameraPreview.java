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
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.YuvImage;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.util.concurrent.ListenableFuture;
import com.ultralytics.ultralytics_yolo.predict.Predictor;

import java.util.concurrent.ExecutionException;
import java.util.Arrays;


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
    private ImageCapture imageCapture;
    private Recording currentRecording;
    private ExecutorService cameraExecutor;
    private boolean isRecording = false;

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

            // Set up video capture
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);

            // Unbind use cases before rebinding
            cameraProvider.unbindAll();

            try {
                // First try to bind all use cases
                Camera camera = cameraProvider.bindToLifecycle(
                        (LifecycleOwner) activity, 
                        cameraSelector, 
                        cameraPreview, 
                        imageAnalysis,
                        imageCapture,
                        videoCapture);
                
                cameraControl = camera.getCameraControl();
                android.util.Log.d("CameraPreview", "Successfully bound all camera use cases");
            } catch (IllegalArgumentException e) {
                // If binding all use cases fails, try without VideoCapture
                android.util.Log.w("CameraPreview", "Failed to bind all use cases: " + e.getMessage());
                android.util.Log.w("CameraPreview", "Trying without video capture");
                
                try {
                    Camera camera = cameraProvider.bindToLifecycle(
                            (LifecycleOwner) activity,
                            cameraSelector,
                            cameraPreview,
                            imageAnalysis,
                            imageCapture);
                    
                    cameraControl = camera.getCameraControl();
                    videoCapture = null; // Mark videoCapture as unavailable
                    android.util.Log.d("CameraPreview", "Successfully bound camera without video capture");
                } catch (IllegalArgumentException e2) {
                    // If that also fails, only bind the essential use cases
                    android.util.Log.w("CameraPreview", "Failed to bind with image capture: " + e2.getMessage());
                    android.util.Log.w("CameraPreview", "Falling back to minimal configuration");
                    
                    Camera camera = cameraProvider.bindToLifecycle(
                            (LifecycleOwner) activity,
                            cameraSelector,
                            cameraPreview,
                            imageAnalysis);
                    
                    cameraControl = camera.getCameraControl();
                    imageCapture = null; // Mark imageCapture as unavailable
                    videoCapture = null; // Mark videoCapture as unavailable
                    android.util.Log.d("CameraPreview", "Bound minimal camera configuration");
                }
            }

            cameraPreview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

            busy = false;
        }
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
            bitmap = toBitmap(image);
            
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
    
    // Convert ImageProxy to Bitmap
    private Bitmap toBitmap(ImageProxy image) {
        // Get the Image from ImageProxy
        Image mediaImage = image.getImage();
        if (mediaImage == null) {
            return null;
        }
        
        // Convert to JPEG bytes
        byte[] jpegBytes;
        if (mediaImage.getFormat() == ImageFormat.YUV_420_888) {
            jpegBytes = yuv420ToJpeg(mediaImage);
        } else {
            // Try standard approach if it's not YUV format
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            jpegBytes = bytes;
        }
        
        // Convert bytes to bitmap
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
    }
    
    // Convert Image to JPEG bytes
    private byte[] imageToJpegBytes(Image image) {
        if (image.getFormat() == ImageFormat.YUV_420_888) {
            return yuv420ToJpeg(image);
        } else {
            android.util.Log.e("CameraPreview", "Unsupported image format: " + image.getFormat());
            return new byte[0];
        }
    }
    
    // Convert YUV_420_888 to JPEG
    private byte[] yuv420ToJpeg(Image image) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        // Get image planes
        Image.Plane[] planes = image.getPlanes();
        
        // Get the YUV data
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        
        byte[] data = new byte[ySize + uSize + vSize];
        
        // Copy Y data
        yBuffer.get(data, 0, ySize);
        // Copy U data
        uBuffer.get(data, ySize, uSize);
        // Copy V data
        vBuffer.get(data, ySize + uSize, vSize);
        
        // Create YuvImage
        YuvImage yuvImage = new YuvImage(
                data,
                ImageFormat.NV21, // Note: We're using NV21 format for YuvImage
                image.getWidth(),
                image.getHeight(),
                null
        );
        
        // Compress to JPEG
        yuvImage.compressToJpeg(
                new Rect(0, 0, image.getWidth(), image.getHeight()),
                100, // Quality (0-100)
                outputStream
        );
        
        return outputStream.toByteArray();
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
            return "Error: Video recording not available on this device";
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
            
            // Clean up old recordings to prevent storage issues
            cleanupOldRecordings(outputDir);
            
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File outputFile = new File(outputDir, "yolo_recording_" + timestamp + ".mp4");
            
            // Set up recording options
            FileOutputOptions fileOutputOptions = new FileOutputOptions.Builder(outputFile).build();
            
            // Signal that recording is starting
            isRecording = true;
            if (predictor != null) {
                predictor.setRecordingMode(true);
            }
            
            // Start recording
            currentRecording = videoCapture.getOutput().prepareRecording(context, fileOutputOptions)
                    .start(ContextCompat.getMainExecutor(context), videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                            if (!finalizeEvent.hasError()) {
                                // Recording saved successfully
                                android.util.Log.d("CameraPreview", "Recording saved successfully to " + outputFile.getAbsolutePath());
                            } else {
                                // Handle recording error
                                android.util.Log.e("CameraPreview", "Recording error: " + finalizeEvent.getError());
                            }
                        }
                    });
            
            android.util.Log.d("CameraPreview", "Started recording to " + outputFile.getAbsolutePath());
            return "Success";
        } catch (Exception e) {
            e.printStackTrace();
            android.util.Log.e("CameraPreview", "Error starting recording: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    // Helper method to clean up old recordings to avoid filling up storage
    private void cleanupOldRecordings(File directory) {
        try {
            File[] files = directory.listFiles();
            if (files == null || files.length <= 5) { // Keep at most 5 recent recordings
                return;
            }
            
            // Sort files by last modified time (oldest first)
            Arrays.sort(files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));
            
            // Delete older files, keeping the 5 most recent
            for (int i = 0; i < files.length - 5; i++) {
                if (files[i].delete()) {
                    android.util.Log.d("CameraPreview", "Deleted old recording: " + files[i].getName());
                }
            }
        } catch (Exception e) {
            android.util.Log.e("CameraPreview", "Error cleaning up old recordings: " + e.getMessage());
        }
    }
    
    public String stopRecording() {
        if (currentRecording == null) {
            return "Error: No active recording";
        }
        
        try {
            currentRecording.stop();
            currentRecording = null;
            // Signal that recording has stopped
            isRecording = false;
            if (predictor != null) {
                predictor.setRecordingMode(false);
            }
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
