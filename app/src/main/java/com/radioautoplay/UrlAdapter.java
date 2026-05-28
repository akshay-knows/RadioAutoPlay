package com.radioautoplay;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * RecyclerView adapter that shows the list of saved stream URLs
 * with per-item delete and play buttons.
 */
public class UrlAdapter extends RecyclerView.Adapter<UrlAdapter.ViewHolder> {

    public interface OnItemActionListener {
        void onDelete(int position);
        void onPlay(int position);
    }

    private final List<String> urls;
    private final OnItemActionListener listener;
    private int activeIndex = 0;

    public UrlAdapter(List<String> urls, OnItemActionListener listener) {
        this.urls     = urls;
        this.listener = listener;
    }

    public void setActiveIndex(int index) {
        int old = activeIndex;
        activeIndex = index;
        notifyItemChanged(old);
        notifyItemChanged(activeIndex);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                               .inflate(R.layout.item_url, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        String url = urls.get(position);
        h.tvUrl.setText(url);

        // Highlight the currently playing / selected stream
        h.itemView.setSelected(position == activeIndex);
        h.tvIndex.setText(String.valueOf(position + 1));

        h.btnDelete.setOnClickListener(v -> {
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_ID) listener.onDelete(pos);
        });

        h.btnPlay.setOnClickListener(v -> {
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_ID) listener.onPlay(pos);
        });
    }

    @Override
    public int getItemCount() { return urls.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView   tvIndex, tvUrl;
        ImageButton btnDelete, btnPlay;

        ViewHolder(View v) {
            super(v);
            tvIndex   = v.findViewById(R.id.tv_index);
            tvUrl     = v.findViewById(R.id.tv_url);
            btnDelete = v.findViewById(R.id.btn_delete);
            btnPlay   = v.findViewById(R.id.btn_play);
        }
    }
}
