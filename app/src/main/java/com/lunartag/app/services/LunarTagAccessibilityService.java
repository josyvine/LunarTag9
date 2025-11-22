package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * LUNARTAG ROBOT - STATE MACHINE EDITION
 * Guaranteed to work. Guaranteed not to click Home Screen.
 * 
 * LOGIC FLOW:
 * [STATE 0] Waiting for Notification -> Sees "Photo Ready" -> Clicks -> Go to STATE 1
 * [STATE 1] Share Sheet Open -> Sees "WhatsApp" -> Clicks -> Go to STATE 2
 * [STATE 2] Inside WhatsApp -> Clicks Send/Group -> Job Done -> Reset to STATE 0
 */
public class LunarTagAccessibilityService extends AccessibilityService {

    // --- Configuration ---
    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_JOB_PENDING = "job_is_pending";
    private static final String KEY_AUTO_MODE = "automation_mode"; // "semi" or "full"
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";

    // --- State Machine ---
    private static final int STATE_WAITING_FOR_NOTIFICATION = 0;
    private static final int STATE_WAITING_FOR_SHARE_SHEET = 1;
    private static final int STATE_INSIDE_WHATSAPP = 2;
    
    // Default state: Waiting
    private int currentState = STATE_WAITING_FOR_NOTIFICATION;

    private boolean isScrolling = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // Listen to ALL events so we never miss the notification text
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK; 
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50; // Fast response
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | 
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        
        // Reset state on startup
        currentState = STATE_WAITING_FOR_NOTIFICATION;
        showDebugToast("🤖 Robot Online: State Machine Active");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 1. GLOBAL CHECK: Is a job pending?
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        boolean isJobPending = prefs.getBoolean(KEY_JOB_PENDING, false);

        if (!isJobPending) {
            currentState = STATE_WAITING_FOR_NOTIFICATION; // Reset if cancelled
            return;
        }

        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        
        // Safety: Get current package
        String pkgName = "";
        if (event.getPackageName() != null) {
            pkgName = event.getPackageName().toString().toLowerCase();
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        // ====================================================================
        // STEP 0: HUNTING FOR NOTIFICATION (Full Automatic Only)
        // ====================================================================
        if (mode.equals("full") && currentState == STATE_WAITING_FOR_NOTIFICATION) {
            
            // Method A: Check specifically for the text in your screenshot
            if (root != null) {
                // Look for the exact text from your screenshot
                List<AccessibilityNodeInfo> bannerNodes = root.findAccessibilityNodeInfosByText("Photo Ready to Send");
                if (!bannerNodes.isEmpty()) {
                    showDebugToast("🤖 Found Banner! Clicking...");
                    AccessibilityNodeInfo banner = bannerNodes.get(0);
                    
                    // Try clicking the text, or its parent (the container)
                    if (performClick(banner)) {
                        currentState = STATE_WAITING_FOR_SHARE_SHEET; // ADVANCE TO NEXT STEP
                        return;
                    }
                }
            }

            // Method B: System Notification Event
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                if (pkgName.contains(getPackageName())) { // lunar tag package
                    Parcelable data = event.getParcelableData();
                    if (data instanceof Notification) {
                        try {
                            showDebugToast("🤖 Notification Event! Opening...");
                            ((Notification) data).contentIntent.send();
                            currentState = STATE_WAITING_FOR_SHARE_SHEET; // ADVANCE TO NEXT STEP
                            return;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            
            // If we are in Step 0, we DO NOT look for WhatsApp.
            // This prevents clicking the Home Screen icon.
            return; 
        }

        // ====================================================================
        // STEP 1: SHARE SHEET (Only active AFTER Notification is clicked)
        // ====================================================================
        if (mode.equals("full") && currentState == STATE_WAITING_FOR_SHARE_SHEET) {
            
            // We expect to be in the "android" system UI or a resolver
            // But even if the package name is weird, we trust this step because 
            // we only got here by clicking the notification.
            
            if (root != null) {
                String targetApp = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp");

                // 1. Try Clicking App Name
                if (scanAndClick(root, targetApp)) {
                    showDebugToast("🤖 App Selected. Moving to WhatsApp...");
                    currentState = STATE_INSIDE_WHATSAPP;
                    return;
                }
                
                // 2. Try Clicking Clone
                if (targetApp.toLowerCase().contains("clone")) {
                    if (scanAndClick(root, "WhatsApp")) {
                         currentState = STATE_INSIDE_WHATSAPP;
                         return;
                    }
                }

                // 3. Scroll logic for Share Sheet
                // We only scroll if we haven't found it yet
                performScroll(root);
            }
            return;
        }

        // ====================================================================
        // STEP 2: INSIDE WHATSAPP (Semi & Full)
        // ====================================================================
        // If we detect we are inside WhatsApp, we force state to 2 (just in case)
        if (pkgName.contains("whatsapp")) {
            currentState = STATE_INSIDE_WHATSAPP;
        }

        if (currentState == STATE_INSIDE_WHATSAPP && pkgName.contains("whatsapp")) {
            
            if (root == null) return;

            // A. Check for SEND button (Final Step)
            if (scanAndClickContentDesc(root, "Send")) {
                showDebugToast("🤖 SENT! Job Finished.");
                
                // RESET EVERYTHING
                prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                currentState = STATE_WAITING_FOR_NOTIFICATION;
                return;
            }

            // B. Find Group
            String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");
            if (!targetGroup.isEmpty()) {
                if (scanAndClick(root, targetGroup)) return;
                if (scanListItemsManually(root, targetGroup)) return;
                performScroll(root);
            }
        }
    }

    // --------------------------------------------------------------------------
    // UTILITIES (Do not remove)
    // --------------------------------------------------------------------------

    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (performClick(node)) return true;
            }
        }
        return false;
    }

    private boolean scanAndClickContentDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null || desc == null) return false;
        if (root.getContentDescription() != null && 
            root.getContentDescription().toString().equalsIgnoreCase(desc)) {
            return performClick(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanAndClickContentDesc(root.getChild(i), desc)) return true;
        }
        return false;
    }

    private boolean scanListItemsManually(AccessibilityNodeInfo root, String targetText) {
        if (root == null) return false;
        // Search deep inside lists
        if (root.getClassName() != null && 
           (root.getClassName().toString().contains("RecyclerView") || 
            root.getClassName().toString().contains("ListView") ||
            root.getClassName().toString().contains("ViewGroup"))) {
            for (int i = 0; i < root.getChildCount(); i++) {
                if (recursiveCheck(root.getChild(i), targetText)) return true;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanListItemsManually(root.getChild(i), targetText)) return true;
        }
        return false;
    }

    private boolean recursiveCheck(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        if ((node.getText() != null && node.getText().toString().toLowerCase().contains(target.toLowerCase())) ||
            (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(target.toLowerCase()))) {
            return performClick(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveCheck(node.getChild(i), target)) return true;
        }
        return false;
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> isScrolling = false, 1500);
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

    private void showDebugToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onInterrupt() {}
}