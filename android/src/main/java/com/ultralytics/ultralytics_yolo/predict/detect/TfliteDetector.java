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
import android.util.Log;

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
    private static final String TAG = "TfliteDetector";

    static {
        System.loadLibrary("ultralytics");
    }

    private static final long FPS_INTERVAL_MS = 1000; // Update FPS every 1000 milliseconds (1 second)
    private static final int NUM_BYTES_PER_CHANNEL = 4;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Matrix transformationMatrix;
    private final Bitmap pendingBitmapFrame;
    // Pre-allocated buffers to avoid GC
    private ByteBuffer imgData;
    private final int[] intValues;
    private ByteBuffer outData;

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
    // Is processing a frame
    private volatile boolean isProcessing = false;

    public TfliteDetector(Context context) {
        super(context);

        pendingBitmapFrame = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        transformationMatrix = new Matrix();
        intValues = new int[INPUT_SIZE * INPUT_SIZE];
        
        // Create initial buffers that will be resized when model loads
        imgData = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * NUM_BYTES_PER_CHANNEL);
        imgData.order(ByteOrder.nativeOrder());
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
        GpuDelegate gpuDelegate = null;
        
        try {
            // Check if GPU support is available
            CompatibilityList compatibilityList = new CompatibilityList();
            if (useGpu && compatibilityList.isDelegateSupportedOnThisDevice()) {
                GpuDelegateFactory.Options delegateOptions = compatibilityList.getBestOptionsForThisDevice();
                gpuDelegate = new GpuDelegate(delegateOptions.setQuantizedModelsAllowed(true));
                interpreterOptions.addDelegate(gpuDelegate);
                Log.d(TAG, "Using GPU delegate");
            } else {
                interpreterOptions.setNumThreads(Math.min(Runtime.getRuntime().availableProcessors(), 4));
                Log.d(TAG, "Using CPU with " + interpreterOptions.getNumThreads() + " threads");
            }
            
            // Create the interpreter
            this.interpreter = new Interpreter(buffer, interpreterOptions);
            
            // Get input tensor info to allocate properly sized buffer
            int[] inputShape = interpreter.getInputTensor(0).shape();
            int inputSize = inputShape[0] * inputShape[1] * inputShape[2] * inputShape[3]; 
            
            if (imgData == null || imgData.capacity() < inputSize * NUM_BYTES_PER_CHANNEL) {
                imgData = ByteBuffer.allocateDirect(inputSize * NUM_BYTES_PER_CHANNEL);
                imgData.order(ByteOrder.nativeOrder());
                Log.d(TAG, "Allocated input buffer with size: " + (inputSize * NUM_BYTES_PER_CHANNEL));
            }
            
            // Initialize output buffer based on model shape
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            outputShape2 = outputShape[1];
            outputShape3 = outputShape[2];
            output = new float[outputShape2][outputShape3];
            
            // Allocate output buffer if needed
            int outputSize = outputShape[0] * outputShape[1] * outputShape[2] * outputShape[3];
            outData = ByteBuffer.allocateDirect(outputSize * NUM_BYTES_PER_CHANNEL);
            outData.order(ByteOrder.nativeOrder());
            
            // Create output map just once
            outputMap = new HashMap<>();
            outputMap.put(0, outData);
            
            // Pre-allocate input array
            inputArray = new Object[]{imgData};
            
            Log.d(TAG, "Model initialized successfully. Input size: " + inputSize + 
                  ", Output size: " + outputSize);
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing interpreter: " + e.getMessage());
            if (gpuDelegate != null) {
                gpuDelegate.close();
            }
            
            // Fallback to basic CPU
            interpreterOptions = new Interpreter.Options();
            interpreterOptions.setNumThreads(2); // Conservative thread count for fallback
            
            try {
                this.interpreter = new Interpreter(buffer, interpreterOptions);
                
                // Get output shape
                int[] outputShape = interpreter.getOutputTensor(0).shape();
                outputShape2 = outputShape[1];
                outputShape3 = outputShape[2];
                output = new float[outputShape2][outputShape3];
                
                // Allocate buffers
                outData = ByteBuffer.allocateDirect(outputShape2 * outputShape3 * NUM_BYTES_PER_CHANNEL);
                outData.order(ByteOrder.nativeOrder());
                
                // Create collections just once
                outputMap = new HashMap<>();
                outputMap.put(0, outData);
                inputArray = new Object[]{imgData};
                
            } catch (Exception fallbackError) {
                Log.e(TAG, "Fallback initialization failed: " + fallbackError.getMessage());
            }
        }
    }

    public void predict(ImageProxy imageProxy, boolean isMirrored) {
        if (interpreter == null || imageProxy == null || isProcessing) {
            // Skip frame if interpreter not ready or already processing a frame
            imageProxy.close();
            return;
        }

        try {
            // Set processing flag to avoid processing multiple frames simultaneously 
            isProcessing = true;
            
            Bitmap bitmap = ImageUtils.toBitmap(imageProxy);
            
            // We're done with the image proxy, release it
            imageProxy.close();
            
            Canvas canvas = new Canvas(pendingBitmapFrame);
            
            // Calculate transformation based on orientation and mirroring
            transformationMatrix.reset();
            
            // Handle rotation based on image rotation
            float rotation = 90; // Default rotation for portrait mode
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
            
            // Recycle bitmap to free memory immediately
            bitmap.recycle();

            // Process in background thread to avoid blocking UI
            new Thread(() -> {
                try {
                    // Prepare input
                    setInput(pendingBitmapFrame);
                    
                    // Run inference
                    long start = System.currentTimeMillis();
                    float[][] result = runInference();
                    long end = System.currentTimeMillis();
                    
                    // If front camera, flip the x coordinates of the bounding boxes
                    if (isMirrored) {
                        for (float[] detection : result) {
                            if (detection != null && detection.length >= 4) {
                                // Flip x coordinate
                                detection[0] = 1.0f - detection[0];
                            }
                        }
                    }
                    
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
                        
                        // Log or display the FPS on main thread
                        final float finalFps = fps;
                        handler.post(() -> {
                            if (fpsRateCallback != null) {
                                fpsRateCallback.onResult(finalFps);
                            }
                        });
                    }
                    
                    // Send results back on main thread
                    final float[][] finalResult = result;
                    final long inferenceTime = end - start;
                    handler.post(() -> {
                        if (objectDetectionResultCallback != null) {
                            objectDetectionResultCallback.onResult(finalResult);
                        }
                        if (inferenceTimeCallback != null) {
                            inferenceTimeCallback.onResult(inferenceTime);
                        }
                        isProcessing = false;
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error in prediction: " + e.getMessage());
                    handler.post(() -> isProcessing = false);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error processing image: " + e.getMessage());
            imageProxy.close();
            isProcessing = false;
        }
    }

    private void setInput(Bitmap resizedbitmap) {
        // Clear the buffer for reuse
        imgData.clear();
        
        // Get pixels
        resizedbitmap.getPixels(intValues, 0, resizedbitmap.getWidth(), 0, 0, 
                              resizedbitmap.getWidth(), resizedbitmap.getHeight());

        // Write normalized pixel values to imgData
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                int pixelValue = intValues[i * INPUT_SIZE + j];
                imgData.putFloat(((pixelValue >> 16) & 0xFF) / 255.0f);
                imgData.putFloat(((pixelValue >> 8) & 0xFF) / 255.0f);
                imgData.putFloat((pixelValue & 0xFF) / 255.0f);
            }
        }
        
        // Reset position for reading
        imgData.rewind();
        
        // Clear output buffer for reuse
        if (outData != null) {
            outData.clear();
        }
    }

    private float[][] runInference() {
        if (interpreter != null) {
            // Run inference
            interpreter.runForMultipleInputsOutputs(inputArray, outputMap);

            ByteBuffer byteBuffer = (ByteBuffer) outputMap.get(0);
            if (byteBuffer != null) {
                // Reset position for reading
                byteBuffer.rewind();

                // Read output data
                for (int j = 0; j < outputShape2; ++j) {
                    for (int k = 0; k < outputShape3; ++k) {
                        if (byteBuffer.remaining() >= 4) { // Each float is 4 bytes
                            output[j][k] = byteBuffer.getFloat();
                        }
                    }
                }

                // Post-process to get detections
                return postprocess(output, outputShape3, outputShape2, (float) confidenceThreshold,
                        (float) iouThreshold, numItemsThreshold, numClasses);
            }
        }
        return new float[0][];
    }

    private native float[][] postprocess(float[][] recognitions, int w, int h,
                                         float confidenceThreshold, float iouThreshold,
                                         int numItemsThreshold, int numClasses);
                                         
    // Release resources when no longer needed
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}

