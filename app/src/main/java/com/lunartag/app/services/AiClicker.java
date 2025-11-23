package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AiClicker {

    private final AccessibilityService service;
    private final TextRecognizer recognizer;
    private final Executor executor;
    private boolean isProcessing = false;

    public AiClicker(AccessibilityService service) {
        this.service = service;
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        this.executor = Executors.newSingleThreadExecutor();
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public void scanAndClickVisual(String targetText, OnScanListener callback) {
        if (isProcessing) {
            if (callback != null) callback.onResult(false);
            return; 
        }
        
        isProcessing = true;

        // VISUAL FEEDBACK: Tells you the scan started
        showToast("👁️ AI Scanning for: " + targetText);

        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(@NonNull AccessibilityService.ScreenshotResult screenshotResult) {
                try {
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(screenshotResult.getHardwareBuffer(), screenshotResult.getColorSpace());
                    if (bitmap != null) {
                        processImage(bitmap, targetText, callback);
                        screenshotResult.getHardwareBuffer().close();
                    } else {
                        finish(callback, false);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    finish(callback, false);
                }
            }

            @Override
            public void onFailure(int i) {
                showToast("⚠️ Screenshot Failed. Code: " + i);
                finish(callback, false);
            }
        });
    }

    private void processImage(Bitmap bitmap, String targetText, OnScanListener callback) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
            .addOnSuccessListener(visionText -> {
                boolean found = false;
                for (Text.TextBlock block : visionText.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        String lineText = line.getText().toLowerCase();
                        
                        // Strict check to find the exact text
                        if (lineText.contains(targetText.toLowerCase())) {
                            Rect box = line.getBoundingBox();
                            if (box != null) {
                                // VISUAL FEEDBACK: Tells you it found it
                                showToast("✅ AI Found: " + targetText);
                                performTap(box.centerX(), box.centerY());
                                found = true;
                                finish(callback, true);
                                return; 
                            }
                        }
                    }
                }
                if (!found) {
                    // showToast("❌ Target not visible"); // Optional: Uncomment to debug
                    finish(callback, false);
                }
            })
            .addOnFailureListener(e -> finish(callback, false));
    }

    private void performTap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        service.dispatchGesture(builder.build(), null, null);
    }

    private void finish(OnScanListener callback, boolean success) {
        isProcessing = false;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onResult(success);
        });
    }

    private void showToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(service.getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    public interface OnScanListener {
        void onResult(boolean success);
    }
}