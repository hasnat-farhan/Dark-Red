package com.antor.sosblue;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter for the list of nearby peer devices found via SOSBlue Mesh.
 */
public class PeerDiscoveryAdapter extends RecyclerView.Adapter<PeerDiscoveryAdapter.ViewHolder> {

    private List<PeerDevice> peers;
    private final OnPeerClickListener listener;

    public interface OnPeerClickListener {
        void onPeerClicked(@NonNull PeerDevice peer);
    }

    public PeerDiscoveryAdapter(@NonNull List<PeerDevice> peers,
                                @NonNull OnPeerClickListener listener) {
        this.peers = peers;
        this.listener = listener;
    }

    public void updatePeers(@NonNull List<PeerDevice> newPeers) {
        this.peers = newPeers;
        notifyDataSetChanged();
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
        PeerDevice peer = peers.get(position);
        holder.nameView.setText(peer.getName());
        holder.detailView.setText(peer.isConnected()
                ? "Connected  ·  signal: " + signalBar(peer.getSignalStrength())
                : "Discovered  ·  signal: " + signalBar(peer.getSignalStrength()));
        holder.itemView.setOnClickListener(v -> listener.onPeerClicked(peer));
    }

    @Override
    public int getItemCount() {
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
            nameView = itemView.findViewById(android.R.id.text1);
            detailView = itemView.findViewById(android.R.id.text2);
        }
    }
}
