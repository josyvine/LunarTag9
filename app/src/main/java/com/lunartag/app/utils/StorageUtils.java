package com.lunartag.app.utils;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import java.io.OutputStream;

/**
 * A dedicated utility to handle Storage Access Framework (SAF).
 * Allows users to select SD Cards or Custom Folders and saves directly to them.
 */
public class StorageUtils {

    private static final String TAG = "StorageUtils";
    private static final String PREFS_STORAGE = "LunarTagStoragePrefs";
    private static final String KEY_CUSTOM_FOLDER_URI = "custom_folder_tree_uri";

    // Request Code to identify when the User returns from the File Picker
    public static final int REQUEST_CODE_PICK_FOLDER = 999;

    /**
     * Step 1: Launch the System File Picker (Folder Browser).
     * Call this when the Folder Icon is clicked.
     */
    public static void launchFolderSelector(Fragment fragment) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); // Crucial for "Forever" access
        
        try {
            fragment.startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER);
            Toast.makeText(fragment.getContext(), "Select a folder to save photos", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(fragment.getContext(), "Error launching File Picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Step 2: Save the permission permanently when the user selects a folder.
     * Call this inside onActivityResult in CameraFragment.
     */
    public static void saveFolderPermission(Context context, Uri treeUri) {
        if (treeUri == null) return;

        ContentResolver resolver = context.getContentResolver();

        // 1. Tell Android "Keep this permission forever"
        try {
            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            resolver.takePersistableUriPermission(treeUri, takeFlags);
        } catch (Exception e) {
            Log.e(TAG, "Failed to take persistable permission: " + e.getMessage());
            // Continue anyway, might work for this session
        }

        // 2. Save the URI string to local settings so we remember it tomorrow
        SharedPreferences prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CUSTOM_FOLDER_URI, treeUri.toString()).apply();

        Toast.makeText(context, "Save Location Updated!", Toast.LENGTH_SHORT).show();
    }

    /**
     * Helper: Check if the user has picked a custom folder previously.
     */
    public static boolean hasCustomFolder(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE);
        String uriString = prefs.getString(KEY_CUSTOM_FOLDER_URI, null);
        return uriString != null && !uriString.isEmpty();
    }

    /**
     * Step 3: The Heavy Lifting. Save the actual photo into that specific folder.
     * Returns the absolute URI string on success, or null on failure.
     */
    @Nullable
    public static String saveImageToCustomFolder(Context context, Bitmap bitmap, String filename) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE);
        String uriString = prefs.getString(KEY_CUSTOM_FOLDER_URI, null);

        if (uriString == null) {
            Log.e(TAG, "No custom folder selected.");
            return null;
        }

        Uri treeUri = Uri.parse(uriString);
        DocumentFile pickedDir = DocumentFile.fromTreeUri(context, treeUri);

        if (pickedDir == null || !pickedDir.canWrite()) {
            Log.e(TAG, "Cannot write to the selected folder. Permission lost or SD Card removed.");
            return null;
        }

        // Create the file (MIME type, Display Name)
        DocumentFile newFile = pickedDir.createFile("image/jpeg", filename + ".jpg");
        
        if (newFile == null) {
            Log.e(TAG, "Failed to create file inside custom folder.");
            return null;
        }

        // Write the Bitmap data
        try (OutputStream out = context.getContentResolver().openOutputStream(newFile.getUri())) {
            if (out == null) return null;
            
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            out.flush();
            
            // Return the usable URI
            return newFile.getUri().toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error writing bitmap to custom folder", e);
            return null;
        }
    }
}