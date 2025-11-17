package com.lunartag.app.ui.admin;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.lunartag.app.R;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class ScheduleAdapter extends RecyclerView.Adapter<ScheduleAdapter.TimestampViewHolder> {

    private final List<Long> timestampList;
    private final SimpleDateFormat dateTimeFormat;
    private final OnTimestampDeleteListener deleteListener;

    public interface OnTimestampDeleteListener {
        void onTimestampDeleted(int position);
    }

    public ScheduleAdapter(List<Long> timestampList, OnTimestampDeleteListener listener) {
        this.timestampList = timestampList;
        this.deleteListener = listener;
        // Formatter for displaying date and time with AM/PM
        this.dateTimeFormat = new SimpleDateFormat("dd-MMM-yyyy hh:mm:ss a", Locale.US);
    }

    @NonNull
    @Override
    public TimestampViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_timestamp_slot, parent, false);
        return new TimestampViewHolder(itemView, deleteListener);
    }

    @Override
    public void onBindViewHolder(@NonNull TimestampViewHolder holder, int position) {
        long timestamp = timestampList.get(position);
        holder.timestampTextView.setText(dateTimeFormat.format(timestamp));
    }

    @Override
    public int getItemCount() {
        return timestampList.size();
    }

    /**
     * The ViewHolder class holds references to the UI views for a single list item.
     */
    static class TimestampViewHolder extends RecyclerView.ViewHolder {
        final TextView timestampTextView;
        final ImageButton deleteButton;

        TimestampViewHolder(@NonNull View itemView, final OnTimestampDeleteListener listener) {
            super(itemView);
            timestampTextView = itemView.findViewById(R.id.text_timestamp);
            deleteButton = itemView.findViewById(R.id.button_delete_timestamp);

            deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        int position = getAdapterPosition();
                        if (position != RecyclerView.NO_POSITION) {
                            listener.onTimestampDeleted(position);
                        }
                    }
                }
            });
        }
    }
}