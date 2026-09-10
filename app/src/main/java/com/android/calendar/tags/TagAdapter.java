package com.android.calendar.tags;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ws.xsoh.etar.R;

/**
 * Adapter for displaying tags in the management list, including the special
 * "Uncategorized" pseudo-tag that is not deletable or editable.
 */
class TagAdapter extends RecyclerView.Adapter<TagAdapter.ViewHolder> {

    private static final int TYPE_TAG = 0;
    private static final int TYPE_UNCATEGORIZED = 1;

    interface Callbacks {
        void onTagClicked(Tag tag);
        void onTagDeleteClicked(Tag tag);
        void onDragStarted(ViewHolder holder);
    }

    private final List<Tag> mItems = new ArrayList<>();
    private final Callbacks mCallbacks;

    TagAdapter(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    void setItems(List<Tag> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    List<Tag> getItems() {
        return new ArrayList<>(mItems);
    }

    boolean isDragEnabled(int position) {
        return true; // all items can be dragged
    }

    @Override
    public int getItemViewType(int position) {
        Tag tag = mItems.get(position);
        return tag.id == TagFilter.TAG_ID_UNCATEGORIZED ? TYPE_UNCATEGORIZED : TYPE_TAG;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.tag_item, parent, false);
        return new ViewHolder(v, viewType == TYPE_TAG);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Tag tag = mItems.get(position);
        holder.name.setText(tag.name);

        // Update color dot
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(tag.color);
        holder.colorDot.setBackground(dot);

        if (holder.isRealTag) {
            holder.deleteBtn.setVisibility(View.VISIBLE);
            holder.itemView.setOnClickListener(v -> mCallbacks.onTagClicked(tag));
            holder.deleteBtn.setOnClickListener(v -> mCallbacks.onTagDeleteClicked(tag));
        } else {
            holder.deleteBtn.setVisibility(View.INVISIBLE);
            holder.itemView.setClickable(false);
        }

        holder.dragHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mCallbacks.onDragStarted(holder);
            }
            return false;
        });
    }

    void onItemMove(int fromPosition, int toPosition) {
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(mItems, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(mItems, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final boolean isRealTag;
        final ImageView dragHandle;
        final View colorDot;
        final TextView name;
        final TextView deleteBtn;

        ViewHolder(View itemView, boolean isRealTag) {
            super(itemView);
            this.isRealTag = isRealTag;
            dragHandle = itemView.findViewById(R.id.drag_handle);
            colorDot = itemView.findViewById(R.id.tag_color_dot);
            name = itemView.findViewById(R.id.tag_name);
            deleteBtn = itemView.findViewById(R.id.tag_delete);
        }
    }
}
