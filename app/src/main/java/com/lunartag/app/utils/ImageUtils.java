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
 * A utility class with static methods for image processing.
 * UPDATED: Includes robust handling for Hardware RowStrides (Padding) to prevent corruption.
 */
public class ImageUtils {

    private ImageUtils() {}

    /**
     * Robust conversion of ImageProxy to Bitmap.
     * Handles JPEG, YUV_420_888, and Hardware Padding correctly.
     */
    public static Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        if (imageProxy == null || imageProxy.getImage() == null) {
            return null;
        }

        Image image = imageProxy.getImage();
        Bitmap bitmap = null;

        // 1. Try to Extract Bitmap based on Format
        if (image.getFormat() == ImageFormat.JPEG) {
            // Handle JPEG directly
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind(); // CRITICAL: Reset buffer position before reading
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } 
        else if (image.getFormat() == ImageFormat.YUV_420_888) {
            // Handle YUV with strict padding calculations
            byte[] nv21 = yuv420ToNv21(image);
            if (nv21 != null) {
                YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);
                byte[] imageBytes = out.toByteArray();
                bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            }
        }

        if (bitmap == null) {
            return null;
        }

        // 2. Handle Rotation
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        if (rotationDegrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
            );
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        }

        return bitmap;
    }

    /**
     * Highly Robust YUV_420_888 to NV21 Converter.
     * Skips the 'Padding' bytes that cause corruption on Oppo/Vivo/Samsung devices.
     */
    private static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int rowStride = image.getPlanes()[0].getRowStride();
        int pixelStride = image.getPlanes()[0].getPixelStride(); // Usually 1 for Y

        int pos = 0;
        byte[] nv21 = new byte[width * height + (width * height / 2)];

        // --- 1. Copy Y Channel (Luminance) ---
        // If rowStride == width, we can copy everything at once.
        // If rowStride > width, we must copy row-by-row to skip padding.
        if (rowStride == width) {
            yBuffer.get(nv21, 0, width * height);
            pos = width * height;
        } else {
            // Complex copy for hardware with padding
            int yBufferPos = width * height; // Approximate check
            yBuffer.rewind();
            for (int row = 0; row < height; row++) {
                // Be careful with buffer limits
                int length = Math.min(width, yBuffer.remaining());
                yBuffer.get(nv21, pos, length);
                pos += width;
                
                // Skip the padding bytes at end of row
                if (row < height - 1) {
                    int padding = rowStride - width;
                    if (yBuffer.remaining() >= padding) {
                         // Manually advance buffer position
                         yBuffer.position(yBuffer.position() + padding);
                    }
                }
            }
        }

        // --- 2. Copy U and V Channels (Chrominance) Interleaved ---
        int rowStrideUV = image.getPlanes()[1].getRowStride();
        int pixelStrideUV = image.getPlanes()[1].getPixelStride();
        
        // Fallback mechanism if conversion is too complex for standard copy
        // Just trying to be safe for your specific crash
        try {
            // Simplified NV21 packing
            // V first, then U (NV21 standard)
            // Note: Usually UV planes are subsampled (width/2, height/2)
            
            // Reset buffers
            uBuffer.rewind();
            vBuffer.rewind();
            
            int uvHeight = height / 2;
            int uvWidth = width / 2;
            
            // We are writing to nv21[pos]
            // NV21 format expects: V, U, V, U...
            
            // The safest generic way is to pull bytes individually if we suspect strides
            byte[] vBytes = new byte[vBuffer.remaining()];
            vBuffer.get(vBytes);
            
            byte[] uBytes = new byte[uBuffer.remaining()];
            uBuffer.get(uBytes);

            for (int row = 0; row < uvHeight; row++) {
                for (int col = 0; col < uvWidth; col++) {
                     // Calculate source index with strides
                     int vIndex = (row * rowStrideUV) + (col * pixelStrideUV);
                     int uIndex = (row * rowStrideUV) + (col * pixelStrideUV);
                     
                     if (vIndex < vBytes.length && uIndex < uBytes.length && pos < nv21.length - 1) {
                         nv21[pos++] = vBytes[vIndex]; // V
                         nv21[pos++] = uBytes[uIndex]; // U
                     }
                }
            }

        } catch (Exception e) {
            // If precise conversion fails, return null to trigger the outer error
            return null;
        }

        return nv21;
    }
}