package com.ultralytics.ultralytics_yolo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import androidx.camera.core.ImageProxy;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ImageUtils {
    public static Bitmap toBitmap(ImageProxy imageProxy) {
        byte[] nv21 = yuv420888ToNv21(imageProxy);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        return yuvImageToBitmap(yuvImage);
    }

    /**
     * Adjusts detection coordinates to match the display correctly
     * This is particularly important when rotating from landscape to portrait and vice-versa
     *
     * @param detections The detection array with coordinates (x, y, width, height, confidence, class)
     * @param isMirrored Whether the image was mirrored (front camera)
     * @param inputSize The input size of the model
     * @param previewWidth The width of the preview
     * @param previewHeight The height of the preview
     * @return The adjusted detections
     */
    public static float[][] adjustDetectionCoordinates(
            float[][] detections, 
            boolean isMirrored,
            int inputSize,
            int previewWidth, 
            int previewHeight) {
            
        if (detections == null) return null;
        
        // Handle portrait mode (most common in mobile apps)
        // In portrait, the camera feed is rotated 90 degrees from the sensor
        boolean isPortrait = previewHeight > previewWidth;
        
        android.util.Log.d("ImageUtils", "Adjusting coordinates - portrait: " + isPortrait + 
                          ", mirrored: " + isMirrored + 
                          ", preview size: " + previewWidth + "x" + previewHeight + 
                          ", model input size: " + inputSize);
        
        for (float[] detection : detections) {
            if (detection == null || detection.length < 6) continue;
            
            // Store original values
            float origX = detection[0];
            float origY = detection[1];
            float origW = detection[2];
            float origH = detection[3];
            
            if (isPortrait) {
                // PROBLEM: The portrait mode transformations need to be fixed
                
                // 1. In portrait mode, x becomes y and y becomes x due to 90 degree rotation
                float tempX = detection[0];
                float tempY = detection[1];
                float tempW = detection[2];
                float tempH = detection[3];
                
                // Handle 90 degree rotation - swap coordinates
                // This makes the top edge of the camera feed the right edge of the screen
                detection[0] = 1.0f - tempY - tempH; // Transform y to x with inversion
                detection[1] = tempX; // Transform x to y
                
                // Also swap width and height
                detection[2] = tempH;
                detection[3] = tempW;
                
                // Handle mirroring for front camera if needed
                if (isMirrored) {
                    // For front camera in portrait, we need to flip the y coordinate
                    detection[1] = 1.0f - detection[1] - detection[3];
                }
            } else {
                // For landscape mode, only need to handle mirroring
                if (isMirrored) {
                    // For mirrored camera in landscape, flip x coordinate
                    detection[0] = 1.0f - detection[0] - detection[2];
                }
            }
            
            // Additional logging for debugging
            android.util.Log.d("ImageUtils", 
                String.format("Detection adjusted: (%.2f, %.2f, %.2f, %.2f) => (%.2f, %.2f, %.2f, %.2f), conf=%.2f, class=%d", 
                origX, origY, origW, origH,
                detection[0], detection[1], detection[2], detection[3], 
                detection[4], (int)detection[5]));
        }
        
        return detections;
    }

    /**
     * Returns a transformation matrix from one reference frame into another. Handles cropping (if
     * maintaining aspect ratio is desired) and rotation.
     *
     * @param srcWidth            Width of source frame.
     * @param srcHeight           Height of source frame.
     * @param dstWidth            Width of destination frame.
     * @param dstHeight           Height of destination frame.
     * @param applyRotation       Amount of rotation to apply from one frame to another. Must be a multiple
     *                            of 90.
     * @param maintainAspectRatio If true, will ensure that scaling in x and y remains constant,
     *                            cropping the image if necessary.
     * @return The transformation fulfilling the desired requirements.
     */
    public static Matrix getTransformationMatrix(
            final int srcWidth,
            final int srcHeight,
            final int dstWidth,
            final int dstHeight,
            final int applyRotation,
            final boolean maintainAspectRatio) {
        final Matrix matrix = new Matrix();

        // Translate so center of image is at origin.
        matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f);

        // Rotate around origin.
        matrix.postRotate(applyRotation);

        // Account for the already applied rotation, if any, and then determine how
        // much scaling is needed for each axis.
        final boolean transpose = (Math.abs(applyRotation) + 90) % 180 == 0;

        final int inWidth = transpose ? srcHeight : srcWidth;
        final int inHeight = transpose ? srcWidth : srcHeight;

        // Apply scaling if necessary.
        if (inWidth != dstWidth || inHeight != dstHeight) {
            final float scaleFactorX = dstWidth / (float) inWidth;
            final float scaleFactorY = dstHeight / (float) inHeight;

            if (maintainAspectRatio) {
                // Scale by minimum factor so that dst is filled completely while
                // maintaining the aspect ratio. Some image may fall off the edge.
                final float scaleFactor = Math.max(scaleFactorX, scaleFactorY);
                matrix.postScale(scaleFactor, scaleFactor);
            } else {
                // Scale exactly to fill dst from src.
                matrix.postScale(scaleFactorX, scaleFactorY);
            }
        }

        if (applyRotation != 0) {
            // Translate back from origin centered reference to destination frame.
            matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f);
        }

        return matrix;
    }
    
    private static Bitmap yuvImageToBitmap(YuvImage yuvImage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out))
            return null;
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private static byte[] yuv420888ToNv21(ImageProxy imageProxy) {
        int pixelCount = imageProxy.getCropRect().width() * imageProxy.getCropRect().height();
        int pixelSizeBits = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888);
        byte[] outputBuffer = new byte[pixelCount * pixelSizeBits / 8];
        imageToByteBuffer(imageProxy, outputBuffer, pixelCount);
        return outputBuffer;
    }

    private static void imageToByteBuffer(ImageProxy imageProxy, byte[] outputBuffer, int pixelCount) {
        assert imageProxy.getFormat() == ImageFormat.YUV_420_888;

        Rect imageCrop = imageProxy.getCropRect();
        ImageProxy.PlaneProxy[] imagePlanes = imageProxy.getPlanes();

        for (int planeIndex = 0; planeIndex < imagePlanes.length; planeIndex++) {
            int outputStride;
            int outputOffset;

            switch (planeIndex) {
                case 0:
                    outputStride = 1;
                    outputOffset = 0;
                    break;
                case 1:
                    outputStride = 2;
                    outputOffset = pixelCount + 1;
                    break;
                case 2:
                    outputStride = 2;
                    outputOffset = pixelCount;
                    break;
                default:
                    return;
            }

            ImageProxy.PlaneProxy plane = imagePlanes[planeIndex];
            ByteBuffer planeBuffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();

            Rect planeCrop = (planeIndex == 0) ?
                    imageCrop :
                    new Rect(imageCrop.left / 2, imageCrop.top / 2, imageCrop.right / 2, imageCrop.bottom / 2);

            int planeWidth = planeCrop.width();
            int planeHeight = planeCrop.height();

            byte[] rowBuffer = new byte[plane.getRowStride()];

            int rowLength = (pixelStride == 1 && outputStride == 1) ? planeWidth : (planeWidth - 1) * pixelStride + 1;

            for (int row = 0; row < planeHeight; row++) {
                planeBuffer.position((row + planeCrop.top) * rowStride + planeCrop.left * pixelStride);

                if (pixelStride == 1 && outputStride == 1) {
                    planeBuffer.get(outputBuffer, outputOffset, rowLength);
                    outputOffset += rowLength;
                } else {
                    planeBuffer.get(rowBuffer, 0, rowLength);
                    for (int col = 0; col < planeWidth; col++) {
                        outputBuffer[outputOffset] = rowBuffer[col * pixelStride];
                        outputOffset += outputStride;
                    }
                }
            }
        }
    }
}
