package com.ultralytics.ultralytics_yolo.predict;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

import androidx.annotation.Keep;
import androidx.camera.core.ImageProxy;

import com.ultralytics.ultralytics_yolo.models.YoloModel;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public abstract class Predictor {
    public static  int INPUT_SIZE = 320;
    protected final Context context;
    public final ArrayList<String> labels = new ArrayList<>();

    // Create a thread pool for handling inference operations
    protected static final ExecutorService inferenceExecutor = Executors.newFixedThreadPool(2);

    static {
        System.loadLibrary("ultralytics");
    }

    protected Predictor(Context context) {
        this.context = context;
    }

    public abstract void loadModel(YoloModel yoloModel, boolean useGpu) throws Exception;

    protected void loadLabels(AssetManager assetManager, String metadataPath) throws IOException {
        InputStream inputStream;
        Yaml yaml = new Yaml();

        // Local metadata file from Flutter project
        if (metadataPath.startsWith("flutter_assets")) {
            inputStream = assetManager.open(metadataPath);
        }
        // Absolute path
        else {
            inputStream = Files.newInputStream(Paths.get(metadataPath));
        }

        Map<String, Object> data = yaml.load(inputStream);
        Map<Integer, String> names = ((Map<Integer, String>) data.get("names"));

        List<Integer> imgszArray = (List<Integer>) data.get("imgsz");    
        if(imgszArray!=null&&imgszArray.size()==2){
            
            INPUT_SIZE = imgszArray.get(0)>=imgszArray.get(1)?imgszArray.get(0):imgszArray.get(1);
            System.out.println("INPUT_SIZE:"+ INPUT_SIZE);
        }  

        labels.clear();
        labels.addAll(names.values());

        inputStream.close();
    }

    public abstract Object predict(Bitmap bitmap);

    public abstract void predict(ImageProxy imageProxy, boolean isMirrored);

    public abstract void setConfidenceThreshold(float confidence);

    public abstract void setInferenceTimeCallback(FloatResultCallback callback);

    public abstract void setFpsRateCallback(FloatResultCallback callback);

    // Method to release resources when predictor is no longer needed
    public void close() {
        // Subclasses should override this method to release their resources
    }

    // Method to shut down the executor service when the app is being closed
    public static void shutdownExecutors() {
        inferenceExecutor.shutdown();
        try {
            // Wait for existing tasks to terminate
            if (!inferenceExecutor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                inferenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            inferenceExecutor.shutdownNow();
        }
    }

    public interface FloatResultCallback {
        @Keep()
        void onResult(float result);
    }
}
