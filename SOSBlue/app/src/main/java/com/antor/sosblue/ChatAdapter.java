package com.antor.sosblue;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple adapter for displaying chat messages in the RecyclerView.
 */
public class ChatAdapter extends ListAdapter<MessageModel, ChatAdapter.ViewHolder> {

    private static final DiffUtil.ItemCallback<MessageModel> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<MessageModel>() {
                @Override
                public boolean areItemsTheSame(@NonNull MessageModel oldItem,
                                               @NonNull MessageModel newItem) {
                    return oldItem.getId() == newItem.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull MessageModel oldItem,
                                                  @NonNull MessageModel newItem) {
                    return oldItem.getText().equals(newItem.getText())
                            && oldItem.isSent() == newItem.isSent();
                }
            };

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    public ChatAdapter() {
        super(DIFF_CALLBACK);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MessageModel msg = getItem(position);
        holder.textView.setText(msg.getText());
        String timestampStr = timeFormat.format(new Date(msg.getTimestamp()));
        String suffix = msg.isSent() ? " ✓" : "";
        holder.subtitleView.setText(timestampStr + suffix);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;
        final TextView subtitleView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(android.R.id.text1);
            subtitleView = itemView.findViewById(android.R.id.text2);
        }
    }
}
