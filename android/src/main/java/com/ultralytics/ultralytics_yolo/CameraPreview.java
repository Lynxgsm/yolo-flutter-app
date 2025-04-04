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

    // Add a dedicated executor for image analysis to avoid blocking the main thread
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();

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
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // Explicitly set format
                            .setTargetResolution(CAMERA_PREVIEW_SIZE) // Set target resolution
                            .build();
            
            // Use dedicated executor instead of Runnable::run for better performance
            imageAnalysis.setAnalyzer(analyzerExecutor, imageProxy -> {
                try {
                    predictor.predict(imageProxy, facing == CameraSelector.LENS_FACING_FRONT);
                } catch (Exception e) {
                    android.util.Log.e("CameraPreview", "Error in image analysis: " + e.getMessage());
                } finally {
                    // Always close the imageProxy to avoid resource leaks
                    imageProxy.close();
                }
            });
            
            // Set up image capture
            imageCapture = new ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build();

            // Set up video capture
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
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
            jpegBytes = yuv420ToJpeg(mediaImage, 90); // Lower quality for better performance
        } else {
            // Try standard approach if it's not YUV format
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            jpegBytes = bytes;
        }
        
        // Configure decoding options to improve performance
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565; // Use RGB_565 instead of ARGB_8888 for better performance
        
        // Convert bytes to bitmap
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
    }
    
    // Convert Image to JPEG bytes
    private byte[] imageToJpegBytes(Image image) {
        if (image.getFormat() == ImageFormat.YUV_420_888) {
            return yuv420ToJpeg(image, 90);
        } else {
            android.util.Log.e("CameraPreview", "Unsupported image format: " + image.getFormat());
            return new byte[0];
        }
    }
    
    // Optimize YUV_420_888 to JPEG conversion with quality parameter
    private byte[] yuv420ToJpeg(Image image, int quality) {
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
        
        // Compress to JPEG with specified quality
        yuvImage.compressToJpeg(
                new Rect(0, 0, image.getWidth(), image.getHeight()),
                quality, // Use specified quality parameter instead of fixed 100
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
            
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File outputFile = new File(outputDir, "yolo_recording_" + timestamp + ".mp4");
            
            // Set up recording options
            FileOutputOptions fileOutputOptions = new FileOutputOptions.Builder(outputFile).build();
            
            try {
                // Start recording with file output
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
                android.util.Log.e("CameraPreview", "Standard recording approach failed: " + e.getMessage());
                
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
                                .withAudioEnabled()
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
                
                e.printStackTrace();
                throw e;
            }
        } catch (Exception e) {
            e.printStackTrace();
            android.util.Log.e("CameraPreview", "Error starting recording: " + e.getMessage());
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
        
        // Shutdown both executors
        cameraExecutor.shutdown();
        analyzerExecutor.shutdown();
    }
}
