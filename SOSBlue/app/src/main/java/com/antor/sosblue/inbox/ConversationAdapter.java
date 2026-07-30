package com.antor.sosblue.inbox;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.sosblue.R;

/**
 * RecyclerView adapter for the conversation inbox list.
 *
 * <p>Uses {@link ListAdapter} with a custom {@link DiffUtil.ItemCallback}
 * so list updates are animated efficiently. Each item shows an avatar
 * (first letter), display name, last message preview, relative timestamp,
 * and an unread count badge.</p>
 */
public class ConversationAdapter extends ListAdapter<ConversationModel, ConversationAdapter.ViewHolder> {

    public interface OnConversationClickListener {
        void onConversationClick(ConversationModel conversation);
    }

    private final OnConversationClickListener clickListener;

    private static final DiffUtil.ItemCallback<ConversationModel> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<ConversationModel>() {
                @Override
                public boolean areItemsTheSame(@NonNull ConversationModel oldItem,
                                                @NonNull ConversationModel newItem) {
                    return oldItem.getConversationId().equals(newItem.getConversationId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull ConversationModel oldItem,
                                                   @NonNull ConversationModel newItem) {
                    return oldItem.getLastTimestamp() == newItem.getLastTimestamp()
                            && oldItem.getLastMessage().equals(newItem.getLastMessage())
                            && oldItem.getUnreadCount() == newItem.getUnreadCount()
                            && oldItem.getDisplayName().equals(newItem.getDisplayName());
                }
            };

    public ConversationAdapter(@NonNull OnConversationClickListener clickListener) {
        super(DIFF_CALLBACK);
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_conversation, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position), clickListener);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final TextView avatarText;
        private final TextView displayName;
        private final TextView lastMessage;
        private final TextView timestampText;
        private final TextView unreadBadge;
        private final TextView mediaIndicator;
        private final TextView transportBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarText = itemView.findViewById(R.id.avatarText);
            displayName = itemView.findViewById(R.id.displayName);
            lastMessage = itemView.findViewById(R.id.lastMessage);
            timestampText = itemView.findViewById(R.id.timestampText);
            unreadBadge = itemView.findViewById(R.id.unreadBadge);
            mediaIndicator = itemView.findViewById(R.id.mediaIndicator);
            transportBadge = itemView.findViewById(R.id.transportBadge);
        }

        void bind(@NonNull ConversationModel conversation,
                  @NonNull OnConversationClickListener clickListener) {

            // Avatar (first letter of display name, fallback to '#')
            avatarText.setText(String.valueOf(conversation.getAvatarChar()));

            // Display name
            displayName.setText(conversation.getDisplayName());

            // Last message preview
            String msg = conversation.getLastMessage();
            if (conversation.hasMedia()) {
                String prefix = conversation.isOutgoing() ? "You: " : "";
                mediaIndicator.setVisibility(View.VISIBLE);
                lastMessage.setText(prefix +
                        (msg.isEmpty() ? "\uD83D\uDCF7 Media" : "\uD83D\uDCF7 " + msg));
            } else {
                mediaIndicator.setVisibility(View.GONE);
                String prefix = conversation.isOutgoing() ? "You: " : "";
                lastMessage.setText(prefix + msg);
            }

            // Relative timestamp
            timestampText.setText(conversation.getRelativeTime());

            // Unread badge
            int unread = conversation.getUnreadCount();
            if (unread > 0) {
                unreadBadge.setVisibility(View.VISIBLE);
                unreadBadge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            } else {
                unreadBadge.setVisibility(View.GONE);
            }

            // Transport badge
            String transportMode = conversation.getLastTransportMode();
            if (transportMode != null && !transportMode.isEmpty()) {
                transportBadge.setVisibility(View.VISIBLE);
                switch (transportMode) {
                    case "SOSBLUE_MESH":
                        transportBadge.setText("MESH");
                        setBadgeStyle(transportBadge, "#2196F3"); // Blue
                        break;
                    case "F2P_SERVERLESS":
                        transportBadge.setText("F2P");
                        setBadgeStyle(transportBadge, "#388E3C"); // Green
                        break;
                    case "SMS_FALLBACK":
                        transportBadge.setText("SMS");
                        setBadgeStyle(transportBadge, "#455A64"); // Blue-grey
                        break;
                    default:
                        transportBadge.setVisibility(View.GONE);
                        break;
                }
            } else {
                transportBadge.setVisibility(View.GONE);
            }

            // Click listener
            itemView.setOnClickListener(v -> clickListener.onConversationClick(conversation));
        }

        private void setBadgeStyle(@NonNull TextView badge, @NonNull String hexColor) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(9f);
            bg.setColor(android.graphics.Color.parseColor(hexColor));
            badge.setBackground(bg);
        }
    }
}
