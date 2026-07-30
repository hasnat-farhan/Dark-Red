package com.antor.sosblue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Chat message adapter with Red & White theme styling.
 * Supports text, image, and video message types with separate layouts
 * for incoming/outgoing and text/media variants.
 */
public class ChatAdapter extends ListAdapter<MessageModel, ChatAdapter.ViewHolder> {

    // View type constants: text vs media × incoming vs outgoing
    private static final int TYPE_TEXT_INCOMING   = 0;
    private static final int TYPE_TEXT_OUTGOING   = 1;
    private static final int TYPE_MEDIA_INCOMING  = 2;
    private static final int TYPE_MEDIA_OUTGOING  = 3;

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
                            && oldItem.isSent() == newItem.isSent()
                            && oldItem.getContentType() == newItem.getContentType();
                }
            };

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    /** Track which message IDs have already been animated. */
    private final Set<Long> animatedIds = new HashSet<>();

    /** Single-thread executor for background bitmap decoding. */
    private static final ExecutorService bitmapExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Listener for download button clicks on incoming media. */
    private OnDownloadClickListener downloadClickListener;

    /**
     * Interface for handling download of received media files.
     */
    public interface OnDownloadClickListener {
        /** Called when the user taps the download button on an incoming media message. */
        void onDownloadClick(MessageModel message);
    }

    /**
     * Sets the listener for download button clicks.
     */
    public void setOnDownloadClickListener(OnDownloadClickListener listener) {
        this.downloadClickListener = listener;
    }

    public ChatAdapter() {
        super(DIFF_CALLBACK);
    }

    @Override
    public int getItemViewType(int position) {
        MessageModel msg = getItem(position);
        boolean isMedia = msg.isMedia();
        boolean isSent = msg.isSent();
        if (isMedia) return isSent ? TYPE_MEDIA_OUTGOING : TYPE_MEDIA_INCOMING;
        return isSent ? TYPE_TEXT_OUTGOING : TYPE_TEXT_INCOMING;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutRes;
        switch (viewType) {
            case TYPE_TEXT_OUTGOING:  layoutRes = R.layout.item_message_outgoing; break;
            case TYPE_MEDIA_INCOMING: layoutRes = R.layout.item_message_media_incoming; break;
            case TYPE_MEDIA_OUTGOING: layoutRes = R.layout.item_message_media_outgoing; break;
            default:                  layoutRes = R.layout.item_message_incoming; break;
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MessageModel msg = getItem(position);
        boolean isMedia = msg.isMedia();

        // ── Common: timestamp ────────────────────────────────────
        String timestampStr = timeFormat.format(new Date(msg.getTimestamp()));
        String suffix = msg.isSent() ? "  ✓✓" : "";
        holder.timestampView.setText(timestampStr + suffix);

        // ── Text message ─────────────────────────────────────────
        if (!isMedia) {
            holder.textView.setText(msg.getText());
            holder.textView.setVisibility(View.VISIBLE);
            if (holder.mediaPreview != null) holder.mediaPreview.setVisibility(View.GONE);
            if (holder.videoPlayIcon != null) holder.videoPlayIcon.setVisibility(View.GONE);
            if (holder.mediaInfo != null) holder.mediaInfo.setVisibility(View.GONE);
            if (holder.mediaProgress != null) holder.mediaProgress.setVisibility(View.GONE);
        }
        // ── Media message (image / video) ────────────────────────
        else {
            // Hide text if empty
            if (msg.getText() != null && !msg.getText().isEmpty()) {
                holder.textView.setText(msg.getText());
                holder.textView.setVisibility(View.VISIBLE);
            } else {
                holder.textView.setVisibility(View.GONE);
            }

            // Load thumbnail from local file URI — off the main thread
            if (holder.mediaPreview != null && msg.getMediaUri() != null) {
                holder.mediaPreview.setVisibility(View.VISIBLE);
                try {
                    Uri uri = Uri.parse(msg.getMediaUri());
                    String path = uri.getPath();
                    if (path != null && new File(path).exists()) {
                        // Set a placeholder while decoding happens off-thread
                        holder.mediaPreview.setImageResource(
                                msg.isVideo() ? R.drawable.feed : R.drawable.nearby);

                        final int adapterPos = holder.getAdapterPosition();
                        final boolean isVideo = msg.isVideo();



                        bitmapExecutor.execute(() -> {
                            try {
                                // First pass: read dimensions only
                                BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
                                boundsOpts.inJustDecodeBounds = true;
                                BitmapFactory.decodeFile(path, boundsOpts);

                                // Calculate down-sample size
                                boundsOpts.inSampleSize = calculateInSampleSize(boundsOpts, 400, 400);
                                boundsOpts.inJustDecodeBounds = false;

                                // Second pass: decode the actual bitmap
                                Bitmap bmp = BitmapFactory.decodeFile(path, boundsOpts);

                                // Post to main thread only if this position is still current
                                mainHandler.post(() -> {
                                    if (holder.getAdapterPosition() != RecyclerView.NO_POSITION
                                            && bmp != null && !bmp.isRecycled()) {
                                        holder.mediaPreview.setImageBitmap(bmp);
                                    }
                                });
                            } catch (Exception e) {
                                mainHandler.post(() -> {
                                    if (holder.getAdapterPosition() != RecyclerView.NO_POSITION) {
                                        holder.mediaPreview.setImageResource(
                                                isVideo ? R.drawable.feed : R.drawable.nearby);
                                    }
                                });
                            }
                        });
                    } else {
                        holder.mediaPreview.setImageResource(
                                msg.isVideo() ? R.drawable.feed : R.drawable.nearby);
                    }
                } catch (Exception e) {
                    holder.mediaPreview.setImageResource(
                            msg.isVideo() ? R.drawable.feed : R.drawable.nearby);
                }
            }

            // Video play icon
            if (holder.videoPlayIcon != null) {
                holder.videoPlayIcon.setVisibility(msg.isVideo() ? View.VISIBLE : View.GONE);
            }

            // File info
            if (holder.mediaInfo != null) {
                String typeLabel = msg.isVideo() ? "🎬" : "🖼";
                String sizeStr = msg.getFormattedSize();
                holder.mediaInfo.setText(typeLabel + " " + sizeStr);
                holder.mediaInfo.setVisibility(View.VISIBLE);
            }

            // ── Download button (incoming media only) ─────────────────
            if (holder.downloadButton != null) {
                if (!msg.isSent() && downloadClickListener != null) {
                    holder.downloadButton.setVisibility(View.VISIBLE);
                    holder.downloadButton.setOnClickListener(v -> {
                        if (downloadClickListener != null) {
                            downloadClickListener.onDownloadClick(msg);
                        }
                    });
                } else {
                    holder.downloadButton.setVisibility(View.GONE);
                    holder.downloadButton.setOnClickListener(null);
                }
            }
        }

        // ── Entrance animation ───────────────────────────────────
        if (!animatedIds.contains(msg.getId())) {
            animatedIds.add(msg.getId());
            android.view.animation.Animation anim =
                    android.view.animation.AnimationUtils.loadAnimation(
                            holder.itemView.getContext(), R.anim.slide_in_bottom);
            holder.itemView.startAnimation(anim);
        }
    }

    /** Calculate in-sampleSize for BitmapFactory to avoid OOM. */
    private static int calculateInSampleSize(BitmapFactory.Options options,
                                              int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // ---------------------------------------------------------------
    //  ViewHolder — unified for text + media layouts
    // ---------------------------------------------------------------

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;
        final TextView timestampView;
        final ImageView mediaPreview;
        final ImageView videoPlayIcon;
        final ImageView downloadButton;
        final TextView mediaInfo;
        final ProgressBar mediaProgress;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.messageText);
            timestampView = itemView.findViewById(R.id.timestampText);
            mediaPreview = itemView.findViewById(R.id.mediaPreview);
            videoPlayIcon = itemView.findViewById(R.id.videoPlayIcon);
            downloadButton = itemView.findViewById(R.id.downloadButton);
            mediaInfo = itemView.findViewById(R.id.mediaInfo);
            mediaProgress = itemView.findViewById(R.id.mediaProgress);
        }
    }
}
