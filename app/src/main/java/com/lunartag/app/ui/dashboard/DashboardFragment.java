package com.lunartag.app.ui.dashboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.lunartag.app.data.AppDatabase;
import com.lunartag.app.databinding.FragmentDashboardBinding;
import com.lunartag.app.model.Photo;
import com.lunartag.app.ui.gallery.GalleryAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardFragment extends Fragment {

    private FragmentDashboardBinding binding;

    // SharedPreferences to store the Shift State permanently
    private static final String PREFS_SHIFT = "LunarTagShiftPrefs";
    private static final String KEY_IS_SHIFT_ACTIVE = "is_shift_active";
    private static final String KEY_LAST_ACTION_TIME = "last_action_time";

    // --- FIX: ADD Database Components ---
    private ExecutorService databaseExecutor;
    private GalleryAdapter galleryAdapter;
    private List<Photo> scheduledPhotoList;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize Executor for DB operations
        databaseExecutor = Executors.newSingleThreadExecutor();
        scheduledPhotoList = new ArrayList<>();

        // Setup the RecyclerView for horizontal scrolling
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false);
        binding.recyclerViewRecentPhotos.setLayoutManager(layoutManager);

        // --- FIX: Initialize Adapter and attach to Recycler ---
        // We use the existing GalleryAdapter to show thumbnails here
        galleryAdapter = new GalleryAdapter(getContext(), scheduledPhotoList);
        binding.recyclerViewRecentPhotos.setAdapter(galleryAdapter);

        // Set click listener for the shift toggle button
        binding.buttonToggleShift.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleShiftState();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // This method is called when the fragment becomes visible.
        // We load the current status and update the UI here.
        updateUI();
        // --- FIX: Load data from DB every time screen appears ---
        loadScheduledPhotos();
    }

    /**
     * NEW METHOD: Query database for PENDING photos and update the list
     */
    private void loadScheduledPhotos() {
        if (getContext() == null) return;

        databaseExecutor.execute(() -> {
            // Query Room Database for Pending photos
            AppDatabase db = AppDatabase.getDatabase(getContext());
            List<Photo> pendingPhotos = db.photoDao().getPendingPhotos();

            // Update UI on Main Thread
            new Handler(Looper.getMainLooper()).post(() -> {
                if (binding != null) {
                    scheduledPhotoList.clear();
                    if (pendingPhotos != null && !pendingPhotos.isEmpty()) {
                        scheduledPhotoList.addAll(pendingPhotos);
                        // If you had an "Empty State" text view, you would hide it here
                    }
                    // Refresh the list on screen
                    galleryAdapter.notifyDataSetChanged();
                }
            });
        });
    }

    /**
     * Reads the current state from SharedPreferences and updates the Button and Text.
     */
    private void updateUI() {
        if (getContext() == null) return;

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_SHIFT, Context.MODE_PRIVATE);
        boolean isShiftActive = prefs.getBoolean(KEY_IS_SHIFT_ACTIVE, false);
        long lastActionTime = prefs.getLong(KEY_LAST_ACTION_TIME, 0);

        if (isShiftActive) {
            // Shift is currently running
            binding.buttonToggleShift.setText("End Shift");

            // Format the start time for display
            String timeStr = "";
            if (lastActionTime > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.US);
                timeStr = " since " + sdf.format(new Date(lastActionTime));
            }
            // Optional: Update status text if present in XML
            // binding.textShiftStatus.setText("On Duty" + timeStr);

        } else {
            // Shift is not running
            binding.buttonToggleShift.setText("Start Shift");
            // binding.textShiftStatus.setText("Off Duty");
        }
    }

    /**
     * Handles the logic when the button is clicked.
     * Switches the state from On -> Off or Off -> On.
     */
    private void toggleShiftState() {
        if (getContext() == null) return;

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_SHIFT, Context.MODE_PRIVATE);
        boolean isCurrentlyActive = prefs.getBoolean(KEY_IS_SHIFT_ACTIVE, false);
        SharedPreferences.Editor editor = prefs.edit();

        if (isCurrentlyActive) {
            // Logic to END the shift
            editor.putBoolean(KEY_IS_SHIFT_ACTIVE, false);
            editor.putLong(KEY_LAST_ACTION_TIME, System.currentTimeMillis());
            editor.apply();

            Toast.makeText(getContext(), "Shift Ended. Good job!", Toast.LENGTH_SHORT).show();
        } else {
            // Logic to START the shift
            editor.putBoolean(KEY_IS_SHIFT_ACTIVE, true);
            editor.putLong(KEY_LAST_ACTION_TIME, System.currentTimeMillis());
            editor.apply();

            Toast.makeText(getContext(), "Shift Started. Tracking active.", Toast.LENGTH_SHORT).show();
        }

        // Refresh the UI immediately
        updateUI();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Important to prevent memory leaks
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
    }
}