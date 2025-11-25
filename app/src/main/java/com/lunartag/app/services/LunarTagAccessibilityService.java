package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_AUTO_MODE = "automation_mode"; 
    private static final String KEY_TARGET_GROUP = "target_group_name";
    
    // Coordinates
    private static final String KEY_ICON_X = "share_icon_x";
    private static final String KEY_ICON_Y = "share_icon_y";

    // STATES
    private static final int STATE_IDLE = 0;
    private static final int STATE_SEARCHING_SHARE_SHEET = 1;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private boolean isScrolling = false;
    private boolean isClickingPending = false; 
    
    // NEW: ONE SHOT FLAG (Prevents machine-gun clicking)
    private boolean hasClickedShareSheet = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK; 
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0; 
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | 
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        // Force Start Overlay
        try {
            Intent intent = new Intent(this, OverlayService.class);
            startService(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentState = STATE_IDLE;
        performBroadcastLog("🔴 ROBOT ONLINE. ONE-SHOT MODE READY.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkgName = event.getPackageName().toString().toLowerCase();

        // 1. IGNORE NOISE
        if (pkgName.contains("inputmethod") || pkgName.contains("systemui")) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");

        if (root == null) return;
        if (isClickingPending) return;

        // 2. CONTEXT DETECTION

        // A. WhatsApp Detection
        boolean isWhatsAppUI = hasText(root, "Send to") || hasText(root, "Recent chats");

        // B. Share Sheet Detection (Stricter)
        // We look for "Cancel" text OR specific chooser packages.
        boolean isShareSheet = hasText(root, "Cancel") || 
                               pkgName.equals("android") || 
                               pkgName.equals("com.android.intentchooser") ||
                               pkgName.contains("chooser");

        // --- LOGIC: RESET ONE-SHOT FLAG ---
        // If we are NOT on the share sheet and NOT in WhatsApp, reset the flag.
        // This means we are back at Camera or Home, ready for a new job.
        if (!isShareSheet && !isWhatsAppUI) {
            hasClickedShareSheet = false; 
        }

        // Safety Return
        if (!isWhatsAppUI && !isShareSheet) {
             return; 
        }

        // ====================================================================
        // 3. SHARE SHEET LOGIC (ONE-SHOT COORDINATE CLICKER)
        // ====================================================================
        if (mode.equals("full") && isShareSheet && !isWhatsAppUI) {

            // CHECK: Did we already click this session?
            if (hasClickedShareSheet) {
                return; // STOP. Do not click again. Wait for WhatsApp to open.
            }

            // 1. Read Coordinates
            int x = prefs.getInt(KEY_ICON_X, 0);
            int y = prefs.getInt(KEY_ICON_Y, 0);

            if (x > 0 && y > 0) {
                // 2. TRIGGER VISUAL BLINK
                if (OverlayService.getInstance() != null) {
                    OverlayService.getInstance().showMarkerAtCoordinate(x, y);
                }

                // 3. EXECUTE BLIND CLICK (With 100ms Duration)
                performBroadcastLog("✅ Share Sheet Detected. Firing ONE SHOT at X=" + x + " Y=" + y);
                dispatchGesture(createClickGesture(x, y), null, null);

                // 4. LOCK THE TRIGGER
                hasClickedShareSheet = true; // Prevent further clicks until menu closes
                
                currentState = STATE_SEARCHING_GROUP;
                
                // Pause to allow app launch
                isClickingPending = true;
                new Handler(Looper.getMainLooper()).postDelayed(() -> isClickingPending = false, 2000);
                return;
            }
        }

        // ====================================================================
        // 4. WHATSAPP LOGIC (TEXT CLICKER - UNTOUCHED)
        // ====================================================================
        if (isWhatsAppUI) {
            // Reset the Share Sheet flag because we have successfully arrived in WhatsApp
            hasClickedShareSheet = false; 

            if (currentState == STATE_IDLE || currentState == STATE_SEARCHING_SHARE_SHEET) {
                performBroadcastLog("⚡ WhatsApp Detected. Searching Group...");
                currentState = STATE_SEARCHING_GROUP;
            }

            // SEARCH FOR GROUP
            if (currentState == STATE_SEARCHING_GROUP) {
                if (targetGroup.isEmpty()) return;

                if (findMarkerAndClick(root, targetGroup, true)) {
                    performBroadcastLog("✅ Group Found. RED LIGHT + CLICK.");
                    currentState = STATE_CLICKING_SEND;
                    return;
                }
                if (!isScrolling) performScroll(root);
            }

            // CLICK SEND
            else if (currentState == STATE_CLICKING_SEND) {
                boolean found = false;
                if (findMarkerAndClickID(root, "com.whatsapp:id/conversation_send_arrow")) found = true;
                if (!found && findMarkerAndClickID(root, "com.whatsapp:id/send")) found = true;
                if (!found && findMarkerAndClick(root, "Send", false)) found = true;

                if (found) {
                    performBroadcastLog("🚀 SENT! Job Done.");
                    currentState = STATE_IDLE;
                }
            }
        }
    }

    // ====================================================================
    // UTILITIES
    // ====================================================================

    // UPDATED: Creates a robust 100ms tap
    private GestureDescription createClickGesture(int x, int y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        
        // Duration 100ms: Ensures Android registers it as a valid tap
        GestureDescription.StrokeDescription clickStroke = 
                new GestureDescription.StrokeDescription(clickPath, 0, 100);
        
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        return clickBuilder.build();
    }

    private boolean hasText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        String cleanTarget = cleanString(text);
        
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(cleanTarget);
        if (nodes != null && !nodes.isEmpty()) return true;

        return recursiveCheckText(root, cleanTarget);
    }

    private boolean recursiveCheckText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && cleanString(node.getText().toString()).contains(text)) return true;
        if (node.getContentDescription() != null && cleanString(node.getContentDescription().toString()).contains(text)) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveCheckText(node.getChild(i), text)) return true;
        }
        return false;
    }

    private boolean findMarkerAndClick(AccessibilityNodeInfo root, String text, boolean isTextSearch) {
        if (root == null || text == null || text.isEmpty()) return false;

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() || node.getParent().isClickable()) {
                    executeVisualClick(node);
                    return true;
                }
            }
        }
        return recursiveSearchAndClick(root, text);
    }

    private boolean findMarkerAndClickID(AccessibilityNodeInfo root, String viewId) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        if (nodes != null && !nodes.isEmpty()) {
            executeVisualClick(nodes.get(0));
            return true;
        }
        return false;
    }

    private boolean recursiveSearchAndClick(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        boolean match = false;
        String cleanTarget = cleanString(text);

        if (node.getText() != null && cleanString(node.getText().toString()).contains(cleanTarget)) match = true;
        if (!match && node.getContentDescription() != null && cleanString(node.getContentDescription().toString()).contains(cleanTarget)) match = true;

        if (match) {
            AccessibilityNodeInfo clickable = node;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }
            if (clickable != null) {
                executeVisualClick(clickable);
                return true;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveSearchAndClick(node.getChild(i), text)) return true;
        }
        return false;
    }

    private String cleanString(String input) {
        if (input == null) return "";
        return input.toLowerCase()
                .replace(" ", "")
                .replace("\n", "")
                .replace("\u200B", "")
                .trim();
    }

    private void executeVisualClick(AccessibilityNodeInfo node) {
        if (isClickingPending) return;
        isClickingPending = true;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (OverlayService.getInstance() != null) {
            OverlayService.getInstance().showMarkerAt(bounds);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            performClick(node);
            isClickingPending = false;
        }, 500); 
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        int attempts = 0;
        while (target != null && attempts < 6) {
            if (target.isClickable()) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            target = target.getParent();
            attempts++;
        }
        return false;
    }

    private void performScroll(AccessibilityNodeInfo root) {
        if (isScrolling) return;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null) {
            isScrolling = true;
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            new Handler(Looper.getMainLooper()).postDelayed(() -> isScrolling = false, 800);
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo res = findScrollable(node.getChild(i));
            if (res != null) return res;
        }
        return null;
    }

    private void performBroadcastLog(String msg) {
        try {
            System.out.println("LUNARTAG_LOG: " + msg);
            Intent intent = new Intent("com.lunartag.ACTION_LOG_UPDATE");
            intent.putExtra("log_msg", msg);
            intent.setPackage(getPackageName());
            getApplicationContext().sendBroadcast(intent);
        } catch (Exception e) {}
    }

    @Override
    public void onInterrupt() {
        currentState = STATE_IDLE;
        if (OverlayService.getInstance() != null) OverlayService.getInstance().hideMarker();
    }
}