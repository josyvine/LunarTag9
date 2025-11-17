package com.lunartag.app.utils;

import androidx.core.content.FileProvider;

/**
 * Custom FileProvider to prevent conflicts with other providers in the app.
 * This is the standard approach for providing file access to other apps.
 */
public class ImageFileProvider extends FileProvider {
    // This class is intentionally empty. Its purpose is to provide a unique authority
    // for the FileProvider declared in the AndroidManifest.xml, which prevents
    // manifest merger conflicts if other libraries also declare a FileProvider.
}