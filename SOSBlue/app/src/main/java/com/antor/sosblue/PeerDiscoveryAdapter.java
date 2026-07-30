package com.antor.sosblue;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Adapter for the list of nearby peer devices found via SOSBlue Mesh.
 */
public class PeerDiscoveryAdapter extends RecyclerView.Adapter<PeerDiscoveryAdapter.ViewHolder> {

    // ── Fix #4: Use CopyOnWriteArrayList so that concurrent iteration
    //    (from background callbacks) and mutation (from the UI thread)
    //    never throw ConcurrentModificationException.
    private volatile CopyOnWriteArrayList<PeerDevice> peers;
    private final OnPeerClickListener listener;

    public interface OnPeerClickListener {
        void onPeerClicked(@NonNull PeerDevice peer);
    }

    public PeerDiscoveryAdapter(@NonNull List<PeerDevice> peers,
                                @NonNull OnPeerClickListener listener) {
        this.peers = new CopyOnWriteArrayList<>(peers);
        this.listener = listener;
    }

    /**
     * Replaces the entire peer list in a thread-safe manner.
     *
     * Fix #4: A new CopyOnWriteArrayList is swapped in atomically
     * so that any in-flight iteration on another thread sees a consistent
     * snapshot.
     */
    public void updatePeers(@NonNull List<PeerDevice> newPeers) {
        int oldSize = getItemCount();
        this.peers = new CopyOnWriteArrayList<>(newPeers);
        int newSize = getItemCount();
        if (newSize > oldSize) {
            notifyItemRangeInserted(oldSize, newSize - oldSize);
        } else if (newSize < oldSize) {
            notifyItemRangeRemoved(newSize, oldSize - newSize);
        }
        int common = Math.min(oldSize, newSize);
        if (common > 0) {
            notifyItemRangeChanged(0, common);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer_dark, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PeerDevice peer = peers.get(position);
        holder.nameView.setText(peer.getName());
        holder.detailView.setText(peer.isConnected()
                ? "Connected  ·  signal: " + signalBar(peer.getSignalStrength())
                : "Discovered  ·  signal: " + signalBar(peer.getSignalStrength()));
        holder.itemView.setOnClickListener(v -> listener.onPeerClicked(peer));
    }

    @Override
    public int getItemCount() {
        // Safe read — snapshot of the volatile reference.
        return peers.size();
    }

    /** Converts 0–4 signal strength to a visual bar string. */
    private static String signalBar(int strength) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(i < strength ? '\u2588' : '\u2591');
        }
        return sb.toString();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView nameView;
        final TextView detailView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.text1);
            detailView = itemView.findViewById(R.id.text2);
        }
    }
}
