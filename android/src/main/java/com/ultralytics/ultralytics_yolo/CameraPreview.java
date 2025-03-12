package com.ultralytics.ultralytics_yolo;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
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

import io.flutter.plugin.common.EventChannel;
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
    
    // Frame streaming
    private EventChannel.EventSink frameStreamSink;
    private boolean isFrameStreamActive = false;

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
                // Process frames for detection
                predictor.predict(imageProxy, facing == CameraSelector.LENS_FACING_FRONT);
                
                // Process frames for stream if active
                if (isFrameStreamActive && frameStreamSink != null) {
                    sendFrameToStream(imageProxy, facing == CameraSelector.LENS_FACING_FRONT);
                }

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
    
    private void sendFrameToStream(ImageProxy imageProxy, boolean isFrontCamera) {
        try {
            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            
            // For YUV format, we need to convert to a format that can be easily used
            Image image = imageProxy.getImage();
            if (image != null) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                YuvImage yuvImage = new YuvImage(
                        getDataFromImage(image),
                        ImageFormat.NV21,
                        image.getWidth(),
                        image.getHeight(),
                        null);
                yuvImage.compressToJpeg(
                        new Rect(0, 0, image.getWidth(), image.getHeight()),
                        100,
                        outputStream);
                byte[] jpegBytes = outputStream.toByteArray();
                
                // Create frame data map
                Map<String, Object> frameData = new HashMap<>();
                frameData.put("bytes", jpegBytes);
                frameData.put("width", image.getWidth());
                frameData.put("height", image.getHeight());
                frameData.put("format", "jpeg");
                frameData.put("rotation", imageProxy.getImageInfo().getRotationDegrees());
                
                // Send frame to Flutter
                activity.runOnUiThread(() -> {
                    if (frameStreamSink != null) {
                        frameStreamSink.success(frameData);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Convert Image to byte array
    private byte[] getDataFromImage(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 4;
        
        byte[] nv21 = new byte[ySize + uvSize * 2];
        
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
        
        int rowStride = image.getPlanes()[0].getRowStride();
        int pixelStride = image.getPlanes()[1].getPixelStride();
        
        // Copy Y plane
        if (rowStride == width) {
            yBuffer.get(nv21, 0, ySize);
        } else {
            byte[] yuvBytes = new byte[rowStride];
            int yPos = 0;
            for (int row = 0; row < height; row++) {
                yBuffer.get(yuvBytes, 0, width);
                System.arraycopy(yuvBytes, 0, nv21, yPos, width);
                yPos += width;
                if (row < height - 1) {
                    yBuffer.position(yBuffer.position() + rowStride - width);
                }
            }
        }
        
        // Interleave U and V planes
        int pos = ySize;
        if (pixelStride == 2 && rowStride == width && uBuffer.remaining() == uvSize) {
            // When pixelStride = 2, buffer contains exactly the amount of data needed
            byte[] ub = new byte[uvSize];
            byte[] vb = new byte[uvSize];
            
            uBuffer.get(ub);
            vBuffer.get(vb);
            
            for (int i = 0; i < uvSize; i++) {
                nv21[pos++] = vb[i];
                nv21[pos++] = ub[i];
            }
        } else {
            // Fallback to slower method for odd strides
            for (int row = 0; row < height / 2; row++) {
                for (int col = 0; col < width / 2; col++) {
                    int uIndex = col * pixelStride + row * rowStride;
                    int vIndex = col * pixelStride + row * rowStride;
                    
                    nv21[pos++] = vBuffer.get(vIndex);
                    nv21[pos++] = uBuffer.get(uIndex);
                }
            }
        }
        
        return nv21;
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
    
    public void setFrameStreamSink(EventChannel.EventSink sink) {
        this.frameStreamSink = sink;
    }
    
    public void startFrameStream() {
        isFrameStreamActive = true;
    }
    
    public void stopFrameStream() {
        isFrameStreamActive = false;
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
        
        isFrameStreamActive = false;
        cameraExecutor.shutdown();
    }
}
