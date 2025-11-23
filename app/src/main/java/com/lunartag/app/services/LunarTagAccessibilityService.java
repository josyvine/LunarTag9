package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_AUTO_MODE = "automation_mode"; 
    private static final String KEY_TARGET_GROUP = "target_group_name";

    // States
    private static final int STATE_IDLE = 0;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private String lastPackageName = "";
    private boolean isScrolling = false;

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

        currentState = STATE_IDLE;

        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), "🤖 ROBOT READY", Toast.LENGTH_LONG).show());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");

        String pkgName = "unknown";
        if (event.getPackageName() != null) {
            pkgName = event.getPackageName().toString().toLowerCase();
        }

        // 1. TRACK PACKAGE CHANGES (Original Logic)
        if (!pkgName.equals(lastPackageName)) {
            if (!lastPackageName.isEmpty()) {
                performBroadcastLog("🔄 App Switch: " + pkgName);
                // Only reset if we are completely leaving the flow
                if (!pkgName.equals("android") && !pkgName.contains("resolver") && !pkgName.contains("whatsapp")) {
                     currentState = STATE_IDLE; 
                }
            }
            lastPackageName = pkgName;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        // ====================================================================
        // FULL AUTOMATIC: CLONE SELECTION (Using scanAndClick logic)
        // ====================================================================
        if (mode.equals("full")) {
            // If we are NOT in WhatsApp yet (we are in the Share List/System Dialog)
            if (!pkgName.contains("whatsapp")) {
                 // Use EXACT same logic as Group Finding: Look for text "WhatsApp (Clone)"
                 if (scanAndClick(root, "WhatsApp (Clone)")) {
                     performBroadcastLog("✅ Full Auto: Found 'WhatsApp (Clone)'. Clicking...");
                     currentState = STATE_SEARCHING_GROUP; // Ready for next step
                     return;
                 }
                 
                 // Backup: Try finding just "Clone" if the full name differs
                 if (scanAndClick(root, "Clone")) {
                     performBroadcastLog("✅ Full Auto: Found 'Clone'. Clicking...");
                     currentState = STATE_SEARCHING_GROUP;
                     return;
                 }
            }
        }

        // ====================================================================
        // SEMI-AUTOMATIC & WHATSAPP HANDLING (ORIGINAL LOGIC RESTORED)
        // ====================================================================
        if (pkgName.contains("whatsapp")) {

            // Trigger Semi-Auto Search
            if (mode.equals("semi")) {
                if (currentState == STATE_IDLE) {
                    performBroadcastLog("⚡ Semi-Auto: WhatsApp Detected. Search Started.");
                    currentState = STATE_SEARCHING_GROUP;
                }
            }
            
            // Trigger Full-Auto Search (If it wasn't set by the Clone click)
            if (mode.equals("full") && currentState == STATE_IDLE) {
                currentState = STATE_SEARCHING_GROUP;
            }

            // STEP 1: SEARCH FOR GROUP
            if (currentState == STATE_SEARCHING_GROUP) {
                if (root == null) return;
                String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");

                if (targetGroup.isEmpty()) {
                    performBroadcastLog("⚠️ Error: No Group Name Saved!");
                    return;
                }

                // USE SCAN AND CLICK (The Logic that works)
                if (scanAndClick(root, targetGroup)) {
                    performBroadcastLog("✅ Found Group: " + targetGroup);
                    currentState = STATE_CLICKING_SEND; // Move to next step
                    return;
                }

                // Scroll if not found
                performBroadcastLog("🔎 Searching for group...");
                performScroll(root);
            }

            // STEP 2: CLICK SEND BUTTON
            else if (currentState == STATE_CLICKING_SEND) {
                if (root == null) return;

                boolean sent = false;
                // Method A: Content Description
                if (scanAndClickContentDesc(root, "Send")) sent = true;

                // Method B: View ID
                if (!sent) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
                    if (!nodes.isEmpty()) {
                        performClick(nodes.get(0));
                        sent = true;
                    }
                }

                if (sent) {
                    performBroadcastLog("🚀 SENT! Job Complete.");
                    currentState = STATE_IDLE; // Reset
                }
            }
        }
    }

    // ====================================================================
    // UTILITIES (ORIGINAL)
    // ====================================================================

    private void performBroadcastLog(String msg) {
        try {
            System.out.println("LUNARTAG_LOG: " + msg);
            Intent intent = new Intent("com.lunartag.ACTION_LOG_UPDATE");
            intent.putExtra("log_msg", msg);
            intent.setPackage(getPackageName());
            getApplicationContext().sendBroadcast(intent);
        } catch (Exception e) {}
    }

    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (performClick(node)) return true;
            }
        }
        return recursiveSearch(root, text);
    }

    private boolean recursiveSearch(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(text.toLowerCase())) {
            return performClick(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveSearch(node.getChild(i), text)) return true;
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> isScrolling = false, 600);
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

    @Override
    public void onInterrupt() {}
}