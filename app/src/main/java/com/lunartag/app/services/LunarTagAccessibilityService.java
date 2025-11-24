package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_AUTO_MODE = "automation_mode"; 
    private static final String KEY_TARGET_GROUP = "target_group_name";
    // NEW: The key for the text you typed in Settings
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";

    // STATES
    private static final int STATE_IDLE = 0;
    private static final int STATE_SEARCHING_SHARE_SHEET = 1;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private boolean isScrolling = false;
    private boolean isClickingPending = false; // Prevents double scanning while waiting for Blink
    
    // SOURCE TRACKING
    private String previousAppPackage = ""; 

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
        
        // START THE OVERLAY SERVICE (So the Red Light is ready)
        try {
            Intent intent = new Intent(this, OverlayService.class);
            startService(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentState = STATE_IDLE;
        performBroadcastLog("🔴 ROBOT ONLINE. VISUAL MARKER READY.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkgName = event.getPackageName().toString().toLowerCase();

        // 1. IGNORE SYSTEM NOISE
        if (pkgName.contains("inputmethod") || pkgName.contains("systemui")) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        AccessibilityNodeInfo root = getRootInActiveWindow();

        // 2. DEFINE TERRITORY
        boolean isWhatsApp = pkgName.contains("whatsapp");
        boolean isShareSheet = pkgName.equals("android") || pkgName.contains("ui") || pkgName.contains("resolver");
        boolean isMyApp = pkgName.contains("lunartag");

        // 3. PERSONAL SAFETY (SOURCE TRACKING)
        if (!isWhatsApp && !isShareSheet) {
            previousAppPackage = pkgName;
            // If user switches to a foreign app, STOP.
            if (!isMyApp && currentState != STATE_IDLE) {
                performBroadcastLog("🛑 Switched App. Robot Stopped.");
                currentState = STATE_IDLE;
                if (OverlayService.getInstance() != null) OverlayService.getInstance().hideMarker();
            }
            return; 
        }

        // If we are waiting for the "Blink" animation, don't scan again
        if (isClickingPending) return;

        // ====================================================================
        // 4. FULL AUTOMATIC: SHARE SHEET (VISUAL CLICK)
        // ====================================================================
        if (mode.equals("full") && isShareSheet) {
            
            if (currentState == STATE_IDLE) currentState = STATE_SEARCHING_SHARE_SHEET;

            if (currentState == STATE_SEARCHING_SHARE_SHEET) {
                // FIX: Read the exact text from Settings
                String targetAppName = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp(Clone)");
                
                // Look for that text
                if (findMarkerAndClick(root, targetAppName, true)) {
                    performBroadcastLog("✅ Found '" + targetAppName + "'. Blinking & Clicking...");
                    currentState = STATE_SEARCHING_GROUP;
                } else {
                    // Not found? Scroll.
                    if (!isScrolling) performScroll(root);
                }
            }
        }

        // ====================================================================
        // 5. WHATSAPP LOGIC (VISUAL CLICK)
        // ====================================================================
        if (isWhatsApp) {
            
            // PERSONAL SAFETY CHECK
            if (previousAppPackage.contains("launcher") || previousAppPackage.contains("home")) {
                if (currentState == STATE_IDLE) return; 
            }

            if (root == null) return;

            // A. TRIGGER: "SEND TO..."
            List<AccessibilityNodeInfo> headers = root.findAccessibilityNodeInfosByText("Send to");
            if (headers != null && !headers.isEmpty()) {
                 if (currentState == STATE_IDLE || currentState == STATE_SEARCHING_SHARE_SHEET) {
                     performBroadcastLog("⚡ 'Send to' detected. Starting Visual Search.");
                     currentState = STATE_SEARCHING_GROUP;
                 }
            }

            // B. SEARCH GROUP (VISUAL)
            if (currentState == STATE_SEARCHING_GROUP) {
                String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");
                if (targetGroup.isEmpty()) return;

                if (findMarkerAndClick(root, targetGroup, true)) {
                    performBroadcastLog("✅ Found Group. Blinking...");
                    currentState = STATE_CLICKING_SEND;
                    return;
                }

                if (!isScrolling) performScroll(root);
            }

            // C. CLICK SEND (VISUAL - USING IDS)
            else if (currentState == STATE_CLICKING_SEND) {
                boolean found = false;

                // 1. Try New WhatsApp ID (Green Arrow)
                if (findMarkerAndClickID(root, "com.whatsapp:id/conversation_send_arrow")) found = true;

                // 2. Try Old WhatsApp ID
                if (!found && findMarkerAndClickID(root, "com.whatsapp:id/send")) found = true;
                
                // 3. Try Content Desc (Fallback)
                if (!found && findMarkerAndClick(root, "Send", false)) found = true;

                if (found) {
                    performBroadcastLog("🚀 SEND FOUND! Blinking...");
                    currentState = STATE_IDLE;
                }
            }
        }
    }

    // ====================================================================
    // VISUAL MARKER LOGIC (THE RED LIGHT)
    // ====================================================================

    /**
     * Finds text, Draws Red Light, Waits, Then Clicks
     */
    private boolean findMarkerAndClick(AccessibilityNodeInfo root, String text, boolean isTextSearch) {
        if (root == null || text == null || text.isEmpty()) return false;
        
        List<AccessibilityNodeInfo> nodes;
        if (isTextSearch) {
            nodes = root.findAccessibilityNodeInfosByText(text);
        } else {
            // Treat 'text' as content description search
            return recursiveSearchAndClick(root, text);
        }

        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() || node.getParent().isClickable()) {
                    executeVisualClick(node);
                    return true;
                }
            }
        }
        
        if (isTextSearch) return recursiveSearchAndClick(root, text);
        return false;
    }

    /**
     * Finds View ID, Draws Red Light, Waits, Then Clicks
     */
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
        
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(text.toLowerCase())) match = true;
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(text.toLowerCase())) match = true;
        
        if (match) {
            executeVisualClick(node);
            return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveSearchAndClick(node.getChild(i), text)) return true;
        }
        return false;
    }

    /**
     * CORE LOGIC: DRAW RED LIGHT -> WAIT -> CLICK
     */
    private void executeVisualClick(AccessibilityNodeInfo node) {
        if (isClickingPending) return;
        isClickingPending = true;

        // 1. GET COORDINATES
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        // 2. SHOW RED BLINKING LIGHT
        if (OverlayService.getInstance() != null) {
            OverlayService.getInstance().showMarkerAt(bounds);
        }

        // 3. WAIT 500ms (FOR USER TO SEE THE LIGHT) THEN CLICK
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