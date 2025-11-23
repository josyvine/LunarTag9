package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * LUNARTAG ROBOT - CRITICAL FIX
 * 
 * 1. REMOVED blocking "JobPending" check that killed the logs.
 * 2. ENABLED Semi-Auto to work anytime you open WhatsApp.
 * 3. ADDED Force-Start if "Photo Ready" notification is seen.
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
    private static final int STATE_CONFIRM_SEND = 3; 

    private int currentState = STATE_WAITING_FOR_NOTIFICATION;
    private boolean isScrolling = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK; 
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50; 
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | 
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        
        currentState = STATE_WAITING_FOR_NOTIFICATION;
        // Delay strictly to ensure Main Activity is ready to receive
        new Handler(Looper.getMainLooper()).postDelayed(() -> 
            broadcastLog("🤖 ROBOT CONNECTED. Listening for events..."), 1000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        boolean isJobPending = prefs.getBoolean(KEY_JOB_PENDING, false);

        // Get package name safely
        String pkgName = "unknown";
        if (event.getPackageName() != null) {
            pkgName = event.getPackageName().toString().toLowerCase();
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        // ====================================================================
        // 1. LOGGING & FORCE START (Fixes Silent Failure)
        // ====================================================================
        // If we see the Notification, we FORCE the job to start, regardless of flags.
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (CharSequence t : texts) {
                    String text = t.toString().toLowerCase();
                    if (text.contains("photo ready")) {
                        broadcastLog("🔔 DETECTED NOTIFICATION: " + text);
                        
                        // If Full Auto, AUTO-ACCEPT the job even if flag was false
                        if (mode.equals("full")) {
                            broadcastLog("⚡ FORCE STARTING Full Auto Job...");
                            prefs.edit().putBoolean(KEY_JOB_PENDING, true).apply();
                            isJobPending = true; // Local override
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 2. SEMI-AUTOMATIC LOGIC (Fixes "Not Working")
        // ====================================================================
        // Logic: If inside WhatsApp AND Mode is Semi -> WAKE UP.
        // We removed the "!isJobPending" check here so it always works for you.
        if (mode.equals("semi") && pkgName.contains("whatsapp")) {
             // If we are just entering WhatsApp, set state
             if (currentState != STATE_INSIDE_WHATSAPP && currentState != STATE_CONFIRM_SEND) {
                 broadcastLog("🤖 SEMI-AUTO: WhatsApp detected. Robot Active.");
                 currentState = STATE_INSIDE_WHATSAPP;
             }
        }

        // ====================================================================
        // 3. FULL AUTOMATIC LOGIC
        // ====================================================================
        
        // ---- STEP 0: NOTIFICATION ----
        if (mode.equals("full") && currentState == STATE_WAITING_FOR_NOTIFICATION) {
            // Only proceed if job is officially pending (or we just forced it above)
            if (!isJobPending) return; 

            // A. Event based (System Tray)
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                Parcelable data = event.getParcelableData();
                if (data instanceof Notification) {
                    List<CharSequence> txt = event.getText();
                    if (txt.toString().toLowerCase().contains("photo ready")) {
                        try {
                            ((Notification) data).contentIntent.send();
                            broadcastLog("✅ Notification Intent Sent. Waiting for Share Sheet...");
                            currentState = STATE_WAITING_FOR_SHARE_SHEET;
                            return;
                        } catch (Exception e) {
                            broadcastLog("❌ Intent Failed: " + e.getMessage());
                        }
                    }
                }
            }

            // B. Screen content based (Banner)
            if (root != null) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Photo Ready to Send");
                if (!nodes.isEmpty()) {
                    AccessibilityNodeInfo node = nodes.get(0);
                    if (performClick(node)) {
                         broadcastLog("✅ Clicked Notification Banner.");
                         currentState = STATE_WAITING_FOR_SHARE_SHEET;
                         return;
                    }
                }
            }
        }

        // ---- STEP 1: SHARE SHEET ----
        if (mode.equals("full") && currentState == STATE_WAITING_FOR_SHARE_SHEET) {
            if (root != null) {
                // Priority: CLONE
                if (scanAndClick(root, "WhatsApp (Clone)")) {
                    broadcastLog("✅ Clone Selected. Moving to WhatsApp...");
                    currentState = STATE_INSIDE_WHATSAPP;
                    return;
                }
                
                // Secondary: Main WhatsApp (Wait for dialog)
                // Only click if we aren't already looking at the clone menu
                if (!pkgName.contains("whatsapp")) {
                    if (scanAndClick(root, "WhatsApp")) {
                         broadcastLog("👆 Clicked WhatsApp. Waiting for Clone Dialog...");
                         // Do not change state yet
                         return;
                    }
                }
                
                performScroll(root);
            }
        }

        // ====================================================================
        // 4. SHARED LOGIC: FIND GROUP & SEND (Semi & Full)
        // ====================================================================
        
        // Only run this if we are definitely in WhatsApp and have a state
        if (pkgName.contains("whatsapp")) {
            
            // PART A: FIND GROUP
            if (currentState == STATE_INSIDE_WHATSAPP) {
                if (root == null) return;
                String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");

                if (scanAndClick(root, targetGroup)) {
                    broadcastLog("✅ Found Group: " + targetGroup);
                    currentState = STATE_CONFIRM_SEND;
                    return;
                }
                // Scroll and retry
                performScroll(root);
            }

            // PART B: CLICK SEND
            if (currentState == STATE_CONFIRM_SEND) {
                if (root == null) return;
                
                boolean sent = false;
                // Try Description
                if (scanAndClickContentDesc(root, "Send")) sent = true;
                // Try ID
                if (!sent) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
                    if (!nodes.isEmpty()) {
                        performClick(nodes.get(0));
                        sent = true;
                    }
                }
                
                if (sent) {
                    broadcastLog("🚀 SENT! Resetting...");
                    prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                    currentState = STATE_WAITING_FOR_NOTIFICATION;
                }
            }
        }
    }

    // --- UTILITIES ---

    private void broadcastLog(String msg) {
        try {
            Intent intent = new Intent("com.lunartag.ACTION_LOG_UPDATE");
            intent.putExtra("log_msg", msg);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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

    @Override
    public void onInterrupt() {
        broadcastLog("⚠️ Robot Interrupted");
    }
}