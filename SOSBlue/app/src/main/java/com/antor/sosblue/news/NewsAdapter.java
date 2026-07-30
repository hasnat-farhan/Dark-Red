package com.antor.sosblue.news;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.sosblue.R;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * RecyclerView adapter for the news broadcast feed.
 *
 * <p>Each item displays the author name, transport badge, timestamp,
 * text body, and optional media thumbnail. Cards follow the existing
 * dark/red theme.</p>
 */
public class NewsAdapter extends ListAdapter<F2PNewsPacket, NewsAdapter.ViewHolder> {

    private static final DiffUtil.ItemCallback<F2PNewsPacket> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<F2PNewsPacket>() {
                @Override
                public boolean areItemsTheSame(@NonNull F2PNewsPacket oldItem,
                                               @NonNull F2PNewsPacket newItem) {
                    return oldItem.getLocalId() == newItem.getLocalId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull F2PNewsPacket oldItem,
                                                  @NonNull F2PNewsPacket newItem) {
                    return oldItem.getTextPayload().equals(newItem.getTextPayload())
                            && oldItem.isRead() == newItem.isRead();
                }
            };

    /** Track which news item IDs have already been animated on first appearance. */
    private final Set<Long> animatedIds = new HashSet<>();

    public NewsAdapter() {
        super(DIFF_CALLBACK);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_news_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        F2PNewsPacket news = getItem(position);

        // ── Author name ────────────────────────────────────────────
        holder.authorView.setText(news.getAuthorName());

        // ── Transport badge (colored chip with white text) ──────────
        String transportLabel = news.getTransportLabel();
        holder.transportBadge.setText(transportLabel);

        // Set badge background tint to the transport colour so it looks
        // like a small coloured chip/pill. White text is always readable.
        int badgeBgColor;
        switch (news.getTransportType()) {
            case SOSBLUE_MESH:
                badgeBgColor = holder.itemView.getContext().getColor(R.color.primary_red);
                break;
            case F2P_SERVERLESS:
                badgeBgColor = holder.itemView.getContext().getColor(R.color.f2p_badge_bg);
                break;
            case SMS_FALLBACK:
                badgeBgColor = holder.itemView.getContext().getColor(R.color.sms_badge_bg);
                break;
            default:
                badgeBgColor = holder.itemView.getContext().getColor(R.color.primary_red);
        }
        holder.transportBadge.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(badgeBgColor));

        // ── Timestamp ──────────────────────────────────────────────
        long now = System.currentTimeMillis();
        long diff = now - news.getTimestamp();
        CharSequence relative;
        if (diff < 60_000) {
            relative = "Just now";
        } else if (diff < 3600_000) {
            relative = diff / 60_000 + "m ago";
        } else if (diff < 86400_000) {
            relative = diff / 3600_000 + "h ago";
        } else {
            relative = DateUtils.formatDateTime(holder.itemView.getContext(),
                    news.getTimestamp(), DateUtils.FORMAT_SHOW_DATE);
        }
        holder.timestampView.setText(relative);

        // ── Text body ──────────────────────────────────────────────
        holder.textBodyView.setText(news.getTextPayload());

        // ── Media attachment indicator ──────────────────────────────
        if (news.hasMedia()) {
            String mime = news.getMediaMimeType();
            if (mime != null && mime.startsWith("video/")) {
                holder.mediaIndicator.setImageResource(R.drawable.ic_rss);
                holder.mediaIndicator.setVisibility(View.VISIBLE);
            } else {
                holder.mediaIndicator.setImageResource(R.drawable.nearby);
                holder.mediaIndicator.setVisibility(View.VISIBLE);
            }
        } else {
            holder.mediaIndicator.setVisibility(View.GONE);
        }

        // ── Read/unread indicator ──────────────────────────────────
        holder.itemView.setAlpha(news.isRead() ? 0.7f : 1.0f);

        // ── Subtle slide-in entrance animation on first appearance ──
        if (!animatedIds.contains(news.getLocalId())) {
            animatedIds.add(news.getLocalId());
            android.view.animation.Animation anim =
                    android.view.animation.AnimationUtils.loadAnimation(
                            holder.itemView.getContext(), R.anim.slide_in_bottom);
            holder.itemView.startAnimation(anim);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView authorView;
        final TextView transportBadge;
        final TextView timestampView;
        final TextView textBodyView;
        final ImageView mediaIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            authorView = itemView.findViewById(R.id.newsAuthor);
            transportBadge = itemView.findViewById(R.id.newsTransportBadge);
            timestampView = itemView.findViewById(R.id.newsTimestamp);
            textBodyView = itemView.findViewById(R.id.newsTextBody);
            mediaIndicator = itemView.findViewById(R.id.newsMediaIndicator);
        }
    }
}
