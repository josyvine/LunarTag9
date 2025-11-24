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
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";
    // NEW: Logic to bridge the Alarm to the Robot
    private static final String KEY_JOB_PENDING = "job_is_pending";

    // STATES
    private static final int STATE_IDLE = 0;
    private static final int STATE_SEARCHING_SHARE_SHEET = 1;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private boolean isScrolling = false;
    private boolean isClickingPending = false; 
    
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
        
        // Start Overlay for Red Light
        try {
            Intent intent = new Intent(this, OverlayService.class);
            startService(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentState = STATE_IDLE;
        performBroadcastLog("🔴 ROBOT ONLINE. WAITING FOR ALARM...");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkgName = event.getPackageName().toString().toLowerCase();

        if (pkgName.contains("inputmethod") || pkgName.contains("systemui")) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        boolean isJobPending = prefs.getBoolean(KEY_JOB_PENDING, false); // Check if Alarm fired recently

        AccessibilityNodeInfo root = getRootInActiveWindow();

        boolean isWhatsApp = pkgName.contains("whatsapp");
        boolean isMyApp = pkgName.contains("lunartag");
        
        // BROADENED SHARE SHEET DETECTION
        // We now include "android", "ui", "resolver", "launcher", and "packageinstaller"
        // This covers Samsung, Pixel, Oppo, Vivo share sheets.
        boolean isLikelyShareSheet = pkgName.equals("android") || pkgName.contains("ui") || 
                                     pkgName.contains("resolver") || pkgName.contains("chooser") ||
                                     pkgName.contains("android.app");

        // ====================================================================
        // 1. PERSONAL SAFETY & TERRITORY LOCK
        // ====================================================================
        // FIX: If a Job is Pending (Full Auto), we IGNORE the "Foreign App" check.
        // We look everywhere until we find the target app.
        if (!isJobPending) {
            if (!isWhatsApp && !isLikelyShareSheet && !isMyApp) {
                previousAppPackage = pkgName;
                if (currentState != STATE_IDLE) {
                    performBroadcastLog("🛑 Switched App. Robot Stopped.");
                    currentState = STATE_IDLE;
                    if (OverlayService.getInstance() != null) OverlayService.getInstance().hideMarker();
                }
                return; 
            }
        }

        if (isClickingPending) return;

        // ====================================================================
        // 2. FULL AUTOMATIC: SHARE SHEET (ANY PACKAGE)
        // ====================================================================
        if (mode.equals("full")) {
            
            // If Alarm fired (Job Pending), immediately start searching
            if (isJobPending) {
                currentState = STATE_SEARCHING_SHARE_SHEET;
            }

            if (currentState == STATE_SEARCHING_SHARE_SHEET) {
                String targetAppName = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp(Clone)");
                
                // Try to find the app visual
                if (findMarkerAndClick(root, targetAppName, true)) {
                    performBroadcastLog("✅ Found '" + targetAppName + "'. Clicking...");
                    
                    // JOB DONE FOR STEP 1. Clear Pending Flag.
                    prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                    currentState = STATE_SEARCHING_GROUP;
                } else {
                    // Only scroll if we are strictly in a system view, to avoid scrolling random apps
                    if (isLikelyShareSheet && !isScrolling) {
                        performScroll(root);
                    }
                }
            }
        }

        // ====================================================================
        // 3. WHATSAPP LOGIC
        // ====================================================================
        if (isWhatsApp) {
            
            if (root == null) return;

            // A. TRIGGER: "SEND TO..."
            // If we see this, we know we are in the sharing screen
            List<AccessibilityNodeInfo> headers = root.findAccessibilityNodeInfosByText("Send to");
            if (headers != null && !headers.isEmpty()) {
                 // Force state if we just arrived here
                 if (currentState == STATE_IDLE || currentState == STATE_SEARCHING_SHARE_SHEET) {
                     performBroadcastLog("⚡ WhatsApp Opened. Searching Group...");
                     currentState = STATE_SEARCHING_GROUP;
                     // Ensure pending job is cleared so we don't search the share sheet inside WhatsApp
                     prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                 }
            }

            // B. SEARCH GROUP
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

            // C. CLICK SEND
            else if (currentState == STATE_CLICKING_SEND) {
                boolean found = false;
                if (findMarkerAndClickID(root, "com.whatsapp:id/conversation_send_arrow")) found = true;
                if (!found && findMarkerAndClickID(root, "com.whatsapp:id/send")) found = true;
                if (!found && findMarkerAndClick(root, "Send", false)) found = true;

                if (found) {
                    performBroadcastLog("🚀 SEND FOUND! Job Complete.");
                    currentState = STATE_IDLE;
                }
            }
        }
    }

    // ====================================================================
    // UTILITIES
    // ====================================================================

    private boolean findMarkerAndClick(AccessibilityNodeInfo root, String text, boolean isTextSearch) {
        if (root == null || text == null || text.isEmpty()) return false;
        
        // 1. Direct Text Search
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() || node.getParent().isClickable()) {
                    executeVisualClick(node);
                    return true;
                }
            }
        }
        // 2. Recursive Search (Matches "WhatsApp(Clone)" even if spaced differently)
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
        
        // Standardize text for comparison (Remove spaces, lowercase)
        String cleanTarget = text.toLowerCase().replace(" ", "");
        
        if (node.getText() != null) {
            String nodeText = node.getText().toString().toLowerCase().replace(" ", "");
            if (nodeText.contains(cleanTarget)) match = true;
        }
        
        if (!match && node.getContentDescription() != null) {
            String desc = node.getContentDescription().toString().toLowerCase().replace(" ", "");
            if (desc.contains(cleanTarget)) match = true;
        }
        
        if (match) {
            // Climb up to find the clickable parent (Important for Grid Icons)
            AccessibilityNodeInfo clickableNode = node;
            while (clickableNode != null && !clickableNode.isClickable()) {
                clickableNode = clickableNode.getParent();
            }
            
            if (clickableNode != null) {
                executeVisualClick(clickableNode);
                return true;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveSearchAndClick(node.getChild(i), text)) return true;
        }
        return false;
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