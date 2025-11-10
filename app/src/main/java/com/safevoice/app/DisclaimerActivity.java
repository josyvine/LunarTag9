package com.safevoice.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.safevoice.app.databinding.ActivityDisclaimerBinding;

/**
 * This is the first screen the user sees.
 * It forces the user to accept the terms of service before using the app.
 * The user's acceptance is stored in SharedPreferences. If already accepted,
 * this activity immediately forwards the user to MainActivity.
 */
public class DisclaimerActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "SafeVoicePrefs";
    private static final String KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted";

    private ActivityDisclaimerBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if the disclaimer has already been accepted on a previous launch.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean hasAccepted = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false);

        if (hasAccepted) {
            // If accepted, skip this screen and go directly to the main app.
            navigateToMainActivity();
            return; // Important to return here to stop further execution of onCreate.
        }

        // If not accepted, set up the view for the user to read and decide.
        binding = ActivityDisclaimerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Set up the click listener for the "Accept" button.
        binding.buttonAccept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // When the user accepts, save this choice to SharedPreferences.
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                editor.putBoolean(KEY_DISCLAIMER_ACCEPTED, true);
                editor.apply();

                // Then, proceed to the main app.
                navigateToMainActivity();
            }
        });

        // Set up the click listener for the "Decline" button.
        binding.buttonDecline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // If the user declines, simply close the application.
                finish();
            }
        });
    }

    /**
     * A helper method to create an Intent for MainActivity, start it,
     * and finish the current DisclaimerActivity so the user cannot navigate back to it.
     */
    private void navigateToMainActivity() {
        Intent intent = new Intent(DisclaimerActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}