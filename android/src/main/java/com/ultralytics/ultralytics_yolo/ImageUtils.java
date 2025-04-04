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
        Image image = imageProxy.getImage();
        if (image == null) return null;

        int previewWidth = imageProxy.getWidth();
        int previewHeight = imageProxy.getHeight();

        if (image.getFormat() == ImageFormat.YUV_420_888) {
            YuvImage yuvImage = yuv420888ToYuvImage(image, previewWidth, previewHeight);
            if (yuvImage != null) {
                // Use a lower quality to improve performance (90 instead of 100)
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), 90, out);
                
                byte[] imageBytes = out.toByteArray();
                
                // Use RGB_565 to reduce memory usage
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                // Allow the decoder to use a bitmap whose pixels can be modified by the system
                options.inMutable = true;
                
                return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
            }
        }
        
        return null;
    }

    /**
     * Efficiently convert an Image in YUV_420_888 format to YuvImage
     */
    private static YuvImage yuv420888ToYuvImage(Image image, int width, int height) {
        try {
            // Get image planes
            Image.Plane[] planes = image.getPlanes();
            if (planes.length < 3) return null;
            
            // We know that YUV_420_888 has 3 planes: Y, U, and V
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();
            
            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();
            
            byte[] nv21 = new byte[ySize + uSize + vSize];
            
            // Copy Y plane
            yBuffer.get(nv21, 0, ySize);
            
            // Copy U and V planes in NV21 format (interleaved)
            int uvPos = ySize;
            // Get strides for efficient conversion
            int yStride = planes[0].getRowStride();
            int uStride = planes[1].getRowStride();
            int vStride = planes[2].getRowStride();
            int uPixelStride = planes[1].getPixelStride();
            int vPixelStride = planes[2].getPixelStride();
            
            // Efficient interleaved UV copying
            int uvHeight = height / 2;
            int uvWidth = width / 2;
            for (int row = 0; row < uvHeight; row++) {
                for (int col = 0; col < uvWidth; col++) {
                    int vuPos = col * uPixelStride + row * uStride;
                    nv21[uvPos++] = vBuffer.get(vuPos);
                    nv21[uvPos++] = uBuffer.get(vuPos);
                }
            }
            
            return new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        } catch (Exception e) {
            android.util.Log.e("ImageUtils", "Error converting YUV to YuvImage: " + e.getMessage());
            return null;
        }
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
