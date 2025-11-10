package com.safevoice.app.firebase;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Manages the dynamic initialization of the Firebase backend.
 * This class allows the app to switch its Firebase project at runtime
 * by loading a user-provided google-services.json file.
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";
    private static final String USER_CONFIG_FILENAME = "user_google_services.json";

    /**
     * Initializes Firebase for the entire application.
     * It first checks for a user-provided configuration file in the app's private storage.
     * If found, it initializes Firebase using that configuration.
     * If not found, it falls back to the default google-services.json bundled with the APK.
     *
     * @param context The application context.
     */
    public static void initialize(Context context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            File userConfigFile = new File(context.getFilesDir(), USER_CONFIG_FILENAME);

            if (userConfigFile.exists()) {
                Log.d(TAG, "User-provided Firebase config found. Initializing...");
                try {
                    // --- THIS IS THE FIX ---
                    // The fromStream() method is deprecated. We now manually parse the JSON
                    // and use the FirebaseOptions.Builder to create the configuration.
                    FirebaseOptions options = buildOptionsFromJson(new FileInputStream(userConfigFile));
                    FirebaseApp.initializeApp(context, options);
                    Log.d(TAG, "Firebase initialized successfully with USER config.");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to read or parse user-provided Firebase config. Falling back to default.", e);
                    initializeAppWithDefault(context);
                }
            } else {
                Log.d(TAG, "No user-provided Firebase config found. Initializing with default.");
                initializeAppWithDefault(context);
            }
        } else {
            Log.d(TAG, "Firebase already initialized.");
        }
    }

    /**
     * Helper method to parse a JSON InputStream and build FirebaseOptions.
     */
    private static FirebaseOptions buildOptionsFromJson(InputStream inputStream) throws IOException, org.json.JSONException {
        // Read the entire file stream into a string
        int size = inputStream.available();
        byte[] buffer = new byte[size];
        inputStream.read(buffer);
        inputStream.close();
        String json = new String(buffer, StandardCharsets.UTF_8);

        // Parse the JSON string
        JSONObject root = new JSONObject(json);
        JSONObject projectInfo = root.getJSONObject("project_info");
        JSONArray clientArray = root.getJSONArray("client");
        JSONObject client = clientArray.getJSONObject(0);
        JSONObject clientInfo = client.getJSONObject("client_info");
        JSONArray apiKeyArray = client.getJSONArray("api_key");
        JSONObject apiKey = apiKeyArray.getJSONObject(0);

        // Build the FirebaseOptions object
        return new FirebaseOptions.Builder()
                .setApiKey(apiKey.getString("current_key"))
                .setApplicationId(clientInfo.getString("mobilesdk_app_id"))
                .setProjectId(projectInfo.getString("project_id"))
                .setStorageBucket(projectInfo.getString("storage_bucket"))
                .build();
    }

    /**
     * Helper method to initialize Firebase using the default bundled configuration.
     * @param context The application context.
     */
    private static void initializeAppWithDefault(Context context) {
        try {
            FirebaseApp.initializeApp(context);
            Log.d(TAG, "Firebase initialized successfully with DEFAULT config.");
        } catch (Exception e) {
            Log.e(TAG, "FATAL: Could not initialize Firebase with default config.", e);
        }
    }

    /**
     * Saves a new google-services.json file provided by the user to the app's private storage.
     *
     * @param context The application context.
     * @param inputStream The InputStream from the user-selected file.
     * @return true if the file was saved successfully, false otherwise.
     */
    public static boolean saveUserFirebaseConfig(Context context, InputStream inputStream) {
        File userConfigFile = new File(context.getFilesDir(), USER_CONFIG_FILENAME);
        OutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(userConfigFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            Log.d(TAG, "Successfully saved user Firebase config to: " + userConfigFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error saving user Firebase config.", e);
            return false;
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams after saving config.", e);
            }
        }
    }
}