package com.ultralytics.ultralytics_yolo;

import static com.ultralytics.ultralytics_yolo.CameraPreview.CAMERA_PREVIEW_SIZE;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;

import com.ultralytics.ultralytics_yolo.models.LocalYoloModel;
import com.ultralytics.ultralytics_yolo.models.RemoteYoloModel;
import com.ultralytics.ultralytics_yolo.models.YoloModel;
import com.ultralytics.ultralytics_yolo.predict.Predictor;
import com.ultralytics.ultralytics_yolo.predict.classify.ClassificationResult;
import com.ultralytics.ultralytics_yolo.predict.classify.Classifier;
import com.ultralytics.ultralytics_yolo.predict.classify.TfliteClassifier;
import com.ultralytics.ultralytics_yolo.predict.detect.Detector;
import com.ultralytics.ultralytics_yolo.predict.detect.TfliteDetector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class MethodCallHandler implements MethodChannel.MethodCallHandler {
    private final Context context;
    private final CameraPreview cameraPreview;
    private Predictor predictor;
    private final ResultStreamHandler resultStreamHandler;
    private final InferenceTimeStreamHandler inferenceTimeStreamHandler;
    private final FpsRateStreamHandler fpsRateStreamHandler;
    private final float widthDp;
    private final float density;
    private final float heightDp;

    public MethodCallHandler(BinaryMessenger binaryMessenger, Context context, CameraPreview cameraPreview) {
        this.context = context;

        this.cameraPreview = cameraPreview;

        EventChannel predictionResultEventChannel = new EventChannel(binaryMessenger, "ultralytics_yolo_prediction_results");
        resultStreamHandler = new ResultStreamHandler();
        predictionResultEventChannel.setStreamHandler(resultStreamHandler);

        EventChannel inferenceTimeEventChannel = new EventChannel(binaryMessenger, "ultralytics_yolo_inference_time");
        inferenceTimeStreamHandler = new InferenceTimeStreamHandler();
        inferenceTimeEventChannel.setStreamHandler(inferenceTimeStreamHandler);

        EventChannel fpsRateEventChannel = new EventChannel(binaryMessenger, "ultralytics_yolo_fps_rate");
        fpsRateStreamHandler = new FpsRateStreamHandler();
        fpsRateEventChannel.setStreamHandler(fpsRateStreamHandler);


        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        int widthPixels = displayMetrics.widthPixels;
        int heightPixels = displayMetrics.heightPixels;
        density = displayMetrics.density;
        widthDp = widthPixels / density;
        // Add 40dp to resolve the discrepancy between Flutter screen and AndroidView
        // caused by the presence of the navigation bar
        heightDp = heightPixels / density + 40;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        String method = call.method;
        switch (method) {
            case "loadModel":
                loadModel(call, result);
                break;
            case "setConfidenceThreshold":
                setConfidenceThreshold(call, result);
                break;
            case "setIouThreshold":
                setIouThreshold(call, result);
                break;
            case "setNumItemsThreshold":
                setNumItemsThreshold(call, result);
                break;
            case "detectImage":
                detectImage(call, result);
                break;
            case "classifyImage":
                classifyImage(call, result);
                break;
            case "setLensDirection":
                setLensDirection(call, result);
                break;
            case "closeCamera":
                closeCamera(call, result);
                break;
            case "startCamera":
                startCamera(call, result);
                break;
            case "pauseLivePrediction":
                pauseLivePrediction(call, result);
                break;
            case "resumeLivePrediction":
                resumeLivePrediction(call, result);
                break;
            case "setZoomRatio":
                setScaleFactor(call, result);
                break;
            case "startRecording":
                startRecording(call, result);
                break;
            case "stopRecording":
                stopRecording(call, result);
                break;
            case "takePictureAsBytes":
                takePictureAsBytes(call, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void loadModel(MethodCall call, MethodChannel.Result result) {
        Map<String, Object> model = call.argument("model");
        if (model == null) {
            result.error("PredictorError", "Invalid model", null);
            return;
        }

        YoloModel yoloModel = null;
        String type = (String) model.get("type");
        String task = (String) model.get("task");
        String format = (String) model.get("format");
        if (Objects.equals(task, "detect")) {
            if (Objects.equals(format, "tflite")) {
                predictor = new TfliteDetector(context);
            }
        } else if (Objects.equals(task, "classify")) {
            if (Objects.equals(format, "tflite")) {
                predictor = new TfliteClassifier(context);
            }
        } else {
            return;
        }

        switch (Objects.requireNonNull(type)) {
            case "local":
                String modelPath = (String) model.get("modelPath");
                String metadataPath = (String) model.get("metadataPath");

                yoloModel = new LocalYoloModel(task, format, modelPath, metadataPath);
                break;
            case "remote":
                String modelUrl = (String) model.get("modelUrl");

                yoloModel = new RemoteYoloModel(modelUrl, task);
                break;
        }

        try {
            Object useGpuObject = call.argument("useGpu");
            boolean useGpu = false;
            if (useGpuObject != null) {
                useGpu = (boolean) useGpuObject;
            }

            predictor.loadModel(yoloModel, true);

            setPredictorFrameProcessor();
            setPredictorCallbacks();

            result.success("Success");
        } catch (Exception e) {
            result.error("PredictorError", "Invalid model", null);
        }
    }

    private void setPredictorFrameProcessor() {
        cameraPreview.setPredictorFrameProcessor(predictor);
    }

    private void setPredictorCallbacks() {
        if (predictor instanceof Detector) {
            // Get camera preview dimensions and calculate scaling factors
            float previewWidth = CAMERA_PREVIEW_SIZE.getWidth();
            float previewHeight = CAMERA_PREVIEW_SIZE.getHeight();
            
            // Determine device orientation
            int orientation = context.getResources().getConfiguration().orientation;
            boolean isPortrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            
            // Calculate scaling based on device orientation
            float newWidth, newHeight, offsetX = 0, offsetY = 0;
            
            // Log dimensions for debugging
            android.util.Log.d("MethodCallHandler", String.format(
                "Screen: %.1f x %.1f dp, Preview: %.1f x %.1f, Portrait: %b",
                widthDp, heightDp, previewWidth, previewHeight, isPortrait));
            
            if (isPortrait) {
                // In portrait mode, the preview is rotated
                // Calculate appropriate width to maintain aspect ratio
                newWidth = heightDp * (previewWidth / previewHeight);
                newHeight = heightDp;
                
                // Center horizontally 
                if (newWidth < widthDp) {
                    offsetX = (widthDp - newWidth) / 2;
                }
            } else {
                // In landscape mode, use full width
                newWidth = widthDp;
                // Calculate appropriate height to maintain aspect ratio
                newHeight = widthDp * (previewHeight / previewWidth);
                
                // Center vertically
                if (newHeight < heightDp) {
                    offsetY = (heightDp - newHeight) / 2;
                }
            }
            
            // Store final dimensions for use in callbacks
            final float finalNewWidth = newWidth;
            final float finalNewHeight = newHeight;
            final float finalOffsetX = offsetX;
            final float finalOffsetY = offsetY;
            final boolean finalIsPortrait = isPortrait;

            android.util.Log.d("MethodCallHandler", String.format(
                "Final display: width=%.1f, height=%.1f, offsetX=%.1f, offsetY=%.1f",
                newWidth, newHeight, offsetX, offsetY));
            
            ((Detector) predictor).setObjectDetectionResultCallback(result -> {
                List<Map<String, Object>> objects = new ArrayList<>();

                for (float[] obj : result) {
                    if (obj == null || obj.length < 6) continue;
                    
                    Map<String, Object> objectMap = new HashMap<>();

                    // Get normalized coordinates (0-1)
                    float x = obj[0]; 
                    float y = obj[1];
                    float width = obj[2];
                    float height = obj[3];
                    
                    // Scale to screen dimensions
                    float screenX = x * finalNewWidth + finalOffsetX;
                    float screenY = y * finalNewHeight + finalOffsetY;
                    float screenWidth = width * finalNewWidth;
                    float screenHeight = height * finalNewHeight;
                    
                    float confidence = obj[4];
                    int index = (int) obj[5];
                    String label = index < predictor.labels.size() ? predictor.labels.get(index) : "";

                    // Log mapping for debugging
                    android.util.Log.d("MethodCallHandler", String.format(
                        "Mapping: (%.3f, %.3f, %.3f, %.3f) -> (%.1f, %.1f, %.1f, %.1f)",
                        x, y, width, height, screenX, screenY, screenWidth, screenHeight));

                    objectMap.put("x", screenX);
                    objectMap.put("y", screenY);
                    objectMap.put("width", screenWidth);
                    objectMap.put("height", screenHeight);
                    objectMap.put("confidence", confidence);
                    objectMap.put("index", index);
                    objectMap.put("label", label);

                    objects.add(objectMap);
                }

                resultStreamHandler.sink(objects);
            });
        } else if (predictor instanceof Classifier) {
            ((Classifier) predictor).setClassificationResultCallback(result -> {
                List<Map<String, Object>> objects = new ArrayList<>();

                for (ClassificationResult classificationResult : result) {
                    Map<String, Object> objectMap = new HashMap<>();

                    objectMap.put("confidence", classificationResult.confidence);
                    objectMap.put("index", classificationResult.index);
                    objectMap.put("label", classificationResult.label);
                    objects.add(objectMap);
                }

                resultStreamHandler.sink(objects);
            });
        }

        predictor.setFpsRateCallback(fpsRateStreamHandler::sink);
        predictor.setInferenceTimeCallback(inferenceTimeStreamHandler::sink);
    }

    private void setConfidenceThreshold(MethodCall call, MethodChannel.Result result) {
        Object confidenceObject = call.argument("confidence");
        if (confidenceObject != null) {
            final double confidence = (double) confidenceObject;
            predictor.setConfidenceThreshold((float) confidence);
        }
    }

    private void setIouThreshold(MethodCall call, MethodChannel.Result result) {
        Object iouObject = call.argument("iou");
        if (iouObject != null) {
            final double iou = (double) iouObject;
            ((Detector) predictor).setIouThreshold((float) iou);
        }
    }

    private void setNumItemsThreshold(MethodCall call, MethodChannel.Result result) {
        Object numItemsObject = call.argument("numItems");
        if (numItemsObject != null) {
            final int numItems = (int) numItemsObject;
            ((Detector) predictor).setNumItemsThreshold(numItems);
        }
    }

    private void setLensDirection(MethodCall call, MethodChannel.Result result) {
        Object directionObject = call.argument("direction");
        if (directionObject != null) {
            final int direction = (int) directionObject;
            cameraPreview.setCameraFacing(direction);
        }
    }

    private void closeCamera(MethodCall call, MethodChannel.Result result) {
        // No need to implement if it's already handled elsewhere
        result.success("Success");
    }

    private void startCamera(MethodCall call, MethodChannel.Result result) {
        // Camera is started by the CameraPreview when it's initialized
        // We just need to ensure predictor is attached
        if (predictor != null) {
            setPredictorFrameProcessor();
            setPredictorCallbacks();
            result.success("Success");
        } else {
            result.error("CAMERA_ERROR", "Predictor not initialized", null);
        }
    }

    private void pauseLivePrediction(MethodCall call, MethodChannel.Result result) {
        // To pause live prediction, we'll detach the predictor from the camera preview
        if (cameraPreview != null) {
            cameraPreview.setPredictorFrameProcessor(null);
            result.success("Success");
        } else {
            result.error("CAMERA_ERROR", "Camera preview not initialized", null);
        }
    }

    private void resumeLivePrediction(MethodCall call, MethodChannel.Result result) {
        // To resume live prediction, we'll reattach the predictor to the camera preview
        if (cameraPreview != null && predictor != null) {
            cameraPreview.setPredictorFrameProcessor(predictor);
            result.success("Success");
        } else {
            result.error("CAMERA_ERROR", "Camera preview or predictor not initialized", null);
        }
    }

    private void detectImage(MethodCall call, MethodChannel.Result result) {
        if (predictor != null) {
            Object imagePathObject = call.argument("imagePath");
            if (imagePathObject != null) {
                final String imagePath = (String) imagePathObject;
                Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
                final float[][] res = (float[][]) predictor.predict(bitmap);

                float scaleFactor = widthDp / bitmap.getWidth();
                float newHeight = bitmap.getHeight() * scaleFactor;
                List<Map<String, Object>> objects = new ArrayList<>();
                for (float[] obj : res) {
                    Map<String, Object> objectMap = new HashMap<>();

                    float x = obj[0] * widthDp;
                    float y = obj[1] * newHeight;
                    float width = obj[2] * widthDp;
                    float height = obj[3] * newHeight;
                    float confidence = obj[4];
                    int index = (int) obj[5];
                    String label = index < predictor.labels.size() ? predictor.labels.get(index) : "";

                    objectMap.put("x", x);
                    objectMap.put("y", y);
                    objectMap.put("width", width);
                    objectMap.put("height", height);
                    objectMap.put("confidence", confidence);
                    objectMap.put("index", index);
                    objectMap.put("label", label);

                    objects.add(objectMap);
                }

                result.success(objects);
            }
        }
    }

    private void classifyImage(MethodCall call, MethodChannel.Result result) {
        if (predictor != null) {
            Object imagePathObject = call.argument("imagePath");
            if (imagePathObject != null) {
                final String imagePath = (String) imagePathObject;
                Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
                final List<ClassificationResult> res = (List<ClassificationResult>) predictor.predict(bitmap);

                List<Map<String, Object>> objects = new ArrayList<>();
                for (ClassificationResult classificationResult : res) {
                    Map<String, Object> objectMap = new HashMap<>();

                    objectMap.put("confidence", classificationResult.confidence);
                    objectMap.put("index", classificationResult.index);
                    objectMap.put("label", classificationResult.label);
                    objects.add(objectMap);
                }

                result.success(objects);
            }
        }
    }


    private void setScaleFactor(MethodCall call, MethodChannel.Result result) {
        Object factorObject = call.argument("ratio");
        if (factorObject != null) {
            final double factor = (double) factorObject;
            cameraPreview.setScaleFactor(factor);
        }
    }

    private void startRecording(MethodCall call, MethodChannel.Result result) {
        try {
            String response = cameraPreview.startRecording();
            if (response.startsWith("Error")) {
                result.error("RECORDING_ERROR", response, null);
            } else {
                result.success("Success");
            }
        } catch (Exception e) {
            result.error("RECORDING_ERROR", "Failed to start recording: " + e.getMessage(), null);
        }
    }

    private void stopRecording(MethodCall call, MethodChannel.Result result) {
        try {
            String response = cameraPreview.stopRecording();
            if (response.startsWith("Error")) {
                result.error("RECORDING_ERROR", response, null);
            } else {
                // Pass the full response including the path if present
                result.success(response);
                android.util.Log.d("MethodCallHandler", "Recording stopped with result: " + response);
            }
        } catch (Exception e) {
            result.error("RECORDING_ERROR", "Failed to stop recording: " + e.getMessage(), null);
        }
    }

    private void takePictureAsBytes(MethodCall call, MethodChannel.Result result) {
        if (cameraPreview != null) {
            cameraPreview.takePictureAsBytes(new CameraPreview.PhotoCaptureCallback() {
                @Override
                public void onCaptureSuccess(byte[] imageBytes) {
                    result.success(imageBytes);
                }

                @Override
                public void onError(String errorMessage) {
                    result.error("PHOTO_ERROR", errorMessage, null);
                }
            });
        } else {
            result.error("CAMERA_ERROR", "Camera preview not initialized", null);
        }
    }
}
