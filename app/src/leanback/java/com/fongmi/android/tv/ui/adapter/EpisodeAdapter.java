package com.fongmi.android.tv.ui.adapter;

import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Episode> mItems;
    private final int maxWidth;
    private int nextFocusDown;
    private int nextFocusUp;
    private int span = 1;

    public EpisodeAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(48);
    }

    /**
     * 用最长的一条剧集名量出单元格宽度：宽度随内容变化，但同一列表里每格一样大。
     */
    public static int cellWidth(List<Episode> items) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(ResUtil.sp2px(16));
        int width = 0;
        for (Episode item : items) width = Math.max(width, (int) paint.measureText(item.getDesc().concat(item.getName())));
        return Math.max(ResUtil.dp2px(48), width + ResUtil.dp2px(28));
    }

    public void setSpan(int span) {
        this.span = Math.max(1, span);
        notifyDataSetChanged();
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public Episode getActivated() {
        return mItems.isEmpty() ? new Episode() : mItems.get(getPosition());
    }

    public Episode getNext() {
        int current = getPosition();
        int max = getItemCount() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    public void setNextFocusDown(int nextFocusDown) {
        this.nextFocusDown = nextFocusDown;
        notifyDataSetChanged();
    }

    public void setNextFocusUp(int nextFocusUp) {
        this.nextFocusUp = nextFocusUp;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode item = mItems.get(position);
        holder.binding.text.setMaxWidth(maxWidth);
        // 只有首行按上、末行按下才交给网格外的控件，其余方向在网格内连续移动
        holder.binding.text.setNextFocusUpId(position < span ? nextFocusUp : View.NO_ID);
        holder.binding.text.setNextFocusDownId(position + span >= mItems.size() ? nextFocusDown : View.NO_ID);
        holder.binding.text.setOnKeyListener((view, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && moveFocus(view, position, span, mItems.size(), keyCode));
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setText(item.getDesc().concat(item.getName()));
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    /**
     * 网格内焦点连续：左右在整张网格里按顺序走（行末接着下一行），上下按列跳。
     * 上下越界返回 false，交给系统去找网格外的控件；左右越界则原地停住。
     */
    public static boolean moveFocus(View view, int position, int span, int count, int keyCode) {
        int target;
        boolean stay = false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            target = position - span;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            target = position + span;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            target = position - 1;
            stay = true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            target = position + 1;
            stay = true;
        } else {
            return false;
        }
        if (target < 0 || target >= count) return stay;
        if (!(view.getParent() instanceof RecyclerView)) return false;
        RecyclerView recycler = (RecyclerView) view.getParent();
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(target);
        if (holder == null) {
            recycler.scrollToPosition(target);
            return true;
        }
        return holder.itemView.requestFocus();
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpisodeBinding binding;

        ViewHolder(@NonNull AdapterEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
