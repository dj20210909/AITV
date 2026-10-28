package com.fongmi.android.tv.ui.dialog;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeItemBinding;
import com.fongmi.android.tv.databinding.DialogEpisodeBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class EpisodeDialog extends BaseAlertDialog {

    private final List<Episode> items = new ArrayList<>();
    private DialogEpisodeBinding binding;
    private ItemAdapter adapter;
    private OnClickListener listener;
    private int span = 1;

    public static EpisodeDialog create() {
        return new EpisodeDialog();
    }

    public EpisodeDialog items(List<Episode> items) {
        this.items.clear();
        if (items != null) this.items.addAll(items);
        return this;
    }

    public EpisodeDialog listener(OnClickListener listener) {
        this.listener = listener;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogEpisodeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        int available = (int) (ResUtil.getScreenWidth() * 0.8) - ResUtil.dp2px(64);
        int cell = cellWidth(items);
        span = Math.max(4, available / cell);
        ViewGroup.LayoutParams params = binding.recycler.getLayoutParams();
        params.height = (int) (ResUtil.getScreenHeight() * 0.55);
        binding.recycler.setLayoutParams(params);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), span));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(span, 6));
        binding.recycler.setAdapter(adapter = new ItemAdapter());
        binding.recycler.post(() -> {
            binding.recycler.scrollToPosition(adapter.getPosition());
            binding.recycler.post(() -> focus(adapter.getPosition()));
        });
    }

    private void focus(int position) {
        RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForLayoutPosition(position);
        if (holder != null) holder.itemView.requestFocus();
    }

    /**
     * 用最长的一条剧集名量出单元格宽度，保证同一行里每格一样大。
     * 弹窗里的字号渲染比测量略胖，这里按 18sp 量并多留一点余量，避免出现省略号。
     */
    private int cellWidth(List<Episode> items) {
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(ResUtil.sp2px(18));
        int width = 0;
        for (Episode item : items) width = Math.max(width, (int) paint.measureText(item.getDesc().concat(item.getName())));
        return Math.max(ResUtil.dp2px(52), width + ResUtil.dp2px(32));
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.8f);
        // 窗口宽度是 onStart 才生效的，绑定过的单元格要按新宽度重新排一次，否则文字会被裁掉
        if (binding != null) binding.recycler.post(() -> {
            if (adapter != null) adapter.notifyDataSetChanged();
            binding.recycler.post(() -> focus(adapter == null ? 0 : adapter.getPosition()));
        });
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    private final class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.Holder> {

        private int getPosition() {
            for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
            return 0;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(AdapterEpisodeItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Episode item = items.get(position);
            holder.binding.text.setText(item.getDesc().concat(item.getName()));
            holder.binding.text.setSelected(item.isSelected());
            // 弹窗里没有其它控件，网格内焦点一直连续，走到边界就停住
            holder.binding.text.setOnKeyListener((view, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && EpisodeAdapter.moveFocus(view, position, span, items.size(), keyCode));
            holder.binding.text.setOnClickListener(view -> {
                if (listener != null) listener.onItemClick(item);
                dismiss();
            });
        }

        private final class Holder extends RecyclerView.ViewHolder {

            private final AdapterEpisodeItemBinding binding;

            Holder(@NonNull AdapterEpisodeItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
