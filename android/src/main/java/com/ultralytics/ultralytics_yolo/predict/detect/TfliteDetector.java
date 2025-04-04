package com.ultralytics.ultralytics_yolo.predict.detect;

import static com.ultralytics.ultralytics_yolo.CameraPreview.CAMERA_PREVIEW_SIZE;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;

import androidx.camera.core.ImageProxy;

import com.ultralytics.ultralytics_yolo.ImageUtils;
import com.ultralytics.ultralytics_yolo.models.LocalYoloModel;
import com.ultralytics.ultralytics_yolo.models.YoloModel;
import com.ultralytics.ultralytics_yolo.predict.PredictorException;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.GpuDelegateFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;


public class TfliteDetector extends Detector {

    static {
        System.loadLibrary("ultralytics");
    }

    private static final long FPS_INTERVAL_MS = 1000; // Update FPS every 1000 milliseconds (1 second)
    private static final int NUM_BYTES_PER_CHANNEL = 4;
    private static final int FRAME_SKIP = 2; // Process every Nth frame to reduce load
    private int frameCounter = 0; // Counter for frame skipping
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Matrix transformationMatrix;
    private final Bitmap pendingBitmapFrame;
    private int numClasses;
    private int frameCount = 0;
    private double confidenceThreshold = 0.25f;
    private double iouThreshold = 0.45f;
    private int numItemsThreshold = 30;
    private Interpreter interpreter;
    private Object[] inputArray;
    private int outputShape2;
    private int outputShape3;
    private float[][] output;
    private long lastFpsTime = System.currentTimeMillis();
    private Map<Integer, Object> outputMap;
    private ObjectDetectionResultCallback objectDetectionResultCallback;
    private FloatResultCallback inferenceTimeCallback;
    private FloatResultCallback fpsRateCallback;

    public TfliteDetector(Context context) {
        super(context);

        pendingBitmapFrame = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        transformationMatrix = new Matrix();
    }

    @Override
    public void loadModel(YoloModel yoloModel, boolean useGpu) throws Exception {
        if (yoloModel instanceof LocalYoloModel) {
            final LocalYoloModel localYoloModel = (LocalYoloModel) yoloModel;

            if (localYoloModel.modelPath == null || localYoloModel.modelPath.isEmpty() ||
                    localYoloModel.metadataPath == null || localYoloModel.metadataPath.isEmpty()) {
                throw new Exception();
            }

            final AssetManager assetManager = context.getAssets();
            loadLabels(assetManager, localYoloModel.metadataPath);
            numClasses = labels.size();
            try {
                MappedByteBuffer modelFile = loadModelFile(assetManager, localYoloModel.modelPath);
                initDelegate(modelFile, useGpu);
            } catch (Exception e) {
                throw new PredictorException("Error model");
            }
        }
    }

    @Override
    public float[][] predict(Bitmap bitmap) {
        try {
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
            setInput(resizedBitmap);
            return runInference();
        } catch (Exception e) {
            return new float[0][];
        }
    }

    @Override
    public void setConfidenceThreshold(float confidence) {
        this.confidenceThreshold = confidence;
    }

    @Override
    public void setIouThreshold(float iou) {
        this.iouThreshold = iou;
    }

    @Override
    public void setNumItemsThreshold(int numItems) {
        this.numItemsThreshold = numItems;
    }

    @Override
    public void setObjectDetectionResultCallback(ObjectDetectionResultCallback callback) {
        objectDetectionResultCallback = callback;
    }

    @Override
    public void setInferenceTimeCallback(FloatResultCallback callback) {
        inferenceTimeCallback = callback;
    }

    @Override
    public void setFpsRateCallback(FloatResultCallback callback) {
        fpsRateCallback = callback;
    }

    private MappedByteBuffer loadModelFile(AssetManager assetManager, String modelPath) throws IOException {
        // Local model from Flutter project
        if (modelPath.startsWith("flutter_assets")) {
            AssetFileDescriptor fileDescriptor = assetManager.openFd(modelPath);
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
        // Absolute path
        else {
            FileInputStream inputStream = new FileInputStream(modelPath);
            FileChannel fileChannel = inputStream.getChannel();
            long declaredLength = fileChannel.size();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, declaredLength);
        }
    }

    private void initDelegate(MappedByteBuffer buffer, boolean useGpu) {
        Interpreter.Options interpreterOptions = new Interpreter.Options();
        try {
            // Check if GPU support is available
            CompatibilityList compatibilityList = new CompatibilityList();
            if (useGpu && compatibilityList.isDelegateSupportedOnThisDevice()) {
                GpuDelegateFactory.Options delegateOptions = compatibilityList.getBestOptionsForThisDevice();
                GpuDelegate gpuDelegate = new GpuDelegate(delegateOptions.setQuantizedModelsAllowed(true));
                interpreterOptions.addDelegate(gpuDelegate);
            } else {
            interpreterOptions.setNumThreads(4);
            }
            // Create the interpreter
            this.interpreter = new Interpreter(buffer, interpreterOptions);
        } catch (Exception e) {
            interpreterOptions = new Interpreter.Options();
            interpreterOptions.setNumThreads(4);
            // Create the interpreter
            this.interpreter = new Interpreter(buffer, interpreterOptions);
        }

        int[] outputShape = interpreter.getOutputTensor(0).shape();
        outputShape2 = outputShape[1];
        outputShape3 = outputShape[2];
        output = new float[outputShape2][outputShape3];
    }

    public void predict(ImageProxy imageProxy, boolean isMirrored) {
        if (interpreter == null || imageProxy == null) {
            return;
        }

        // Skip frames to improve performance
        frameCounter++;
        if (frameCounter % FRAME_SKIP != 0) {
            return;
        }

        Bitmap bitmap = ImageUtils.toBitmap(imageProxy);
        if (bitmap == null) {
            return;
        }

        // Reuse existing bitmap to reduce garbage collection
        Canvas canvas = new Canvas(pendingBitmapFrame);
        pendingBitmapFrame.eraseColor(0); // Clear bitmap before drawing

        // Calculate transformation based on orientation and mirroring
        transformationMatrix.reset();
        
        // Handle rotation based on image rotation
        float rotation = imageProxy.getImageInfo().getRotationDegrees();
        float centerX = INPUT_SIZE / 2f;
        float centerY = INPUT_SIZE / 2f;
        
        transformationMatrix.postRotate(rotation, centerX, centerY);
        
        // Handle mirroring for front camera
        if (isMirrored) {
            transformationMatrix.postScale(-1, 1, centerX, centerY);
        }
        
        // Scale the image to fit INPUT_SIZE
        float scaleX = (float) INPUT_SIZE / bitmap.getWidth();
        float scaleY = (float) INPUT_SIZE / bitmap.getHeight();
        float scale = Math.max(scaleX, scaleY);
        transformationMatrix.postScale(scale, scale, centerX, centerY);
        
        // Center the image
        float dx = centerX - (bitmap.getWidth() * scale) / 2;
        float dy = centerY - (bitmap.getHeight() * scale) / 2;
        transformationMatrix.postTranslate(dx, dy);
        
        canvas.drawBitmap(bitmap, transformationMatrix, null);
        
        // Recycle the bitmap to free memory immediately
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }

        handler.post(() -> {
            setInput(pendingBitmapFrame);

            long start = System.currentTimeMillis();
            float[][] result = runInference();
            
            // If front camera, flip the x coordinates of the bounding boxes
            if (isMirrored) {
                for (float[] detection : result) {
                    if (detection != null && detection.length >= 4) {
                        // Flip x coordinate
                        detection[0] = 1.0f - detection[0];
                    }
                }
            }
            
            long end = System.currentTimeMillis();

            // Increment frame count
            frameCount++;

            // Check if it's time to update FPS
            long elapsedMillis = end - lastFpsTime;
            if (elapsedMillis > FPS_INTERVAL_MS) {
                // Calculate frames per second
                float fps = (float) frameCount / elapsedMillis * 1000.f;

                // Reset counters for the next interval
                lastFpsTime = end;
                frameCount = 0;

                // Log FPS
                android.util.Log.d("TfliteDetector", "FPS: " + fps);
                
                // Send FPS to callback
                fpsRateCallback.onResult(fps);
            }

            // Send inference time to callback
            inferenceTimeCallback.onResult(end - start);
            
            // Send detection results to callback
            objectDetectionResultCallback.onResult(result);
        });
    }

    private void setInput(Bitmap resizedbitmap) {
        if (inputArray == null) {
            inputArray = new Object[1];
            inputArray[0] = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * NUM_BYTES_PER_CHANNEL);
            ((ByteBuffer) inputArray[0]).order(ByteOrder.nativeOrder());
        }
        
        ByteBuffer inputBuffer = (ByteBuffer) inputArray[0];
        inputBuffer.rewind();
        
        int[] intValues = new int[INPUT_SIZE * INPUT_SIZE];
        resizedbitmap.getPixels(intValues, 0, resizedbitmap.getWidth(), 0, 0, 
                               resizedbitmap.getWidth(), resizedbitmap.getHeight());
        
        int pixel = 0;
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                final int val = intValues[pixel++];
                // Normalize pixel values to [-1,1]
                inputBuffer.putFloat(((val >> 16) & 0xFF) / 127.5f - 1.0f);
                inputBuffer.putFloat(((val >> 8) & 0xFF) / 127.5f - 1.0f);
                inputBuffer.putFloat((val & 0xFF) / 127.5f - 1.0f);
            }
        }
    }

    private float[][] runInference() {
        if (outputMap == null) {
            outputMap = new HashMap<>();
            outputMap.put(0, output);
        }
        
        // Run inference
        interpreter.runForMultipleInputsOutputs(inputArray, outputMap);
        
        // Postprocess the output
        return postprocess(output, 640, 640, confidenceThreshold, iouThreshold, numItemsThreshold);
    }

    private native float[][] postprocess(float[][] recognitions, int w, int h, 
                                        double confidence_threshold, double iou_threshold, int num_items_threshold);
}
