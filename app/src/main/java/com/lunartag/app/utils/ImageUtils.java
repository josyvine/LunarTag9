package com.lunartag.app.utils;

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

/**
 * A utility class with static methods for image processing,
 * particularly for converting CameraX ImageProxy objects to Bitmaps
 * and handling rotation.
 */
public class ImageUtils {

    // Private constructor to prevent instantiation of this utility class.
    private ImageUtils() {}

    /**
     * Converts an ImageProxy object (typically in YUV_420_888 format) to a Bitmap,
     * correcting for the rotation of the camera sensor.
     *
     * @param imageProxy The ImageProxy from the camera.
     * @return A Bitmap representation of the image, correctly rotated.
     */
    public static Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        if (imageProxy == null || imageProxy.getImage() == null) {
            return null;
        }

        Image image = imageProxy.getImage();
        
        // 1. Convert YUV to Byte Array
        byte[] bytes = yuv420toJpeg(image);
        if (bytes == null) {
            return null;
        }

        // 2. Decode Byte Array to Bitmap
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        // 3. Handle Rotation (CRITICAL FOR FRONT CAMERA)
        // CameraX provides the rotation degrees needed to make the image upright.
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        
        if (rotationDegrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            // Re-create the bitmap with the rotation applied
            Bitmap rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
            );
            // Recycle the old bitmap to save memory
            if (rotatedBitmap != bitmap) {
                bitmap.recycle();
            }
            return rotatedBitmap;
        }

        return bitmap;
    }

    /**
     * Helper to convert YUV_420_888 image to JPEG byte array.
     */
    private static byte[] yuv420toJpeg(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            // Fallback for other formats if needed
            return null; 
        }

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);
        return out.toByteArray();
    }
} 