/*
 * Derived from exteraGram feed architecture.
 * Copyright © exteraGram, 2022-2026. GNU GPL v3 or later; see repository
 * ADDITIONAL_TERMS.md and THIRD_PARTY_NOTICES.md.
 * TrustedGram adaptation: basic native feed host for Telegram Android 13.1.1.
 */
package com.exteragram.messenger.feed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.feed.FeedController;
import com.exteragram.messenger.feed.FeedConfig;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Feed tab host. FeedController builds a merged, locally cached timeline from
 * subscribed channels; tapping a post opens its original channel message.
 */
public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private FeedController controller;
    private RecyclerView listView;
    private LinearLayout categoryStrip;
    private FeedAdapter adapter;
    private int classGuid;
    private int loadIndex;
    private boolean loadingRequested;

    @Override
    public boolean onFragmentCreate() {
        controller = FeedController.getInstance(currentAccount);
        classGuid = org.telegram.tgnet.ConnectionsManager.generateClassGuid();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagesDidLoad);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(8));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("Feed");
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView manage = new TextView(context);
        manage.setText("Channels");
        manage.setTextSize(14);
        manage.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        manage.setPadding(dp(12), dp(8), dp(12), dp(8));
        manage.setOnClickListener(v -> presentFragment(new FeedChannelsActivity()));
        header.addView(manage, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP));

        HorizontalScrollView categoriesScroll = new HorizontalScrollView(context);
        categoriesScroll.setHorizontalScrollBarEnabled(false);
        categoryStrip = new LinearLayout(context);
        categoryStrip.setOrientation(LinearLayout.HORIZONTAL);
        categoriesScroll.addView(categoryStrip, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        content.addView(categoriesScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP));
        updateCategoryTabs();

        listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(8), 0, dp(16));
        adapter = new FeedAdapter(context);
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= adapter.getItemCount() - 4) {
                    loadMore();
                }
            }
        });
        content.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (controller != null) {
            controller.setUiActive(true);
            controller.setUiResumed(true);
            refresh();
        }
    }

    @Override
    public void onPause() {
        if (controller != null) {
            controller.setUiResumed(false);
            controller.setUiActive(false);
        }
        super.onPause();
    }

    private void updateCategoryTabs() {
        if (categoryStrip == null || controller == null) return;
        categoryStrip.removeAllViews();
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        addCategoryButton("All", 0, config.getSelectedCategoryId() == 0);
        for (FeedConfig.Category category : config.getVisibleCategories()) {
            addCategoryButton(category.title, category.id, config.getSelectedCategoryId() == category.id);
        }
    }

    private void addCategoryButton(String label, int categoryId, boolean selected) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(7), dp(14), dp(7));
        button.setTextColor(Theme.getColor(selected ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteBlackText));
        button.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.getColor(selected ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(7);
        categoryStrip.addView(button, lp);
        button.setOnClickListener(v -> {
            if (controller != null && controller.selectCategory(categoryId)) {
                updateCategoryTabs();
                refresh();
            }
        });
    }

    private void refresh() {
        if (controller == null || listView == null) return;
        updateCategoryTabs();
        adapter.notifyDataSetChanged();
        if (!loadingRequested && controller.getMessages().isEmpty()) {
            loadingRequested = true;
            controller.loadInitial(classGuid, loadIndex++);
        }
    }

    private void loadMore() {
        if (controller == null || loadingRequested || controller.isLoading()) return;
        loadingRequested = true;
        if (!controller.loadMore(classGuid, loadIndex++)) {
            loadingRequested = false;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) return;
        if (id == NotificationCenter.feedNeedReload) {
            loadingRequested = false;
            refresh();
        } else if (id == NotificationCenter.messagesDidLoad) {
            if (args.length > 10 && args[10] instanceof Integer && (Integer) args[10] == classGuid) {
                loadingRequested = false;
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagesDidLoad);
        if (controller != null) {
            controller.setUiResumed(false);
            controller.setUiActive(false);
        }
        super.onFragmentDestroy();
    }

    private final class FeedAdapter extends RecyclerView.Adapter<FeedHolder> {
        private final Context context;
        FeedAdapter(Context context) { this.context = context; }

        @NonNull
        @Override
        public FeedHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView text = new TextView(context);
            text.setTextSize(15);
            text.setTextColor(Theme.getColor(Theme.key_windowTextBlack));
            text.setPadding(dp(16), dp(14), dp(16), dp(14));
            text.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.getColor(Theme.key_windowBackgroundWhite)));
            text.setGravity(Gravity.START);
            return new FeedHolder(text);
        }

        @Override
        public void onBindViewHolder(@NonNull FeedHolder holder, int position) {
            ArrayList<MessageObject> messages = controller.getMessages();
            if (position < 0 || position >= messages.size()) return;
            MessageObject message = messages.get(position);
            TLRPC.Chat channel = MessagesController.getInstance(currentAccount).getChat((int) -message.getDialogId());
            String channelName = channel != null ? channel.title : "Channel";
            CharSequence body = message.messageOwner != null ? message.messageOwner.message : "";
            if (body == null || body.length() == 0) body = "Media post";
            holder.text.setText(channelName + "\n\n" + body);
            holder.text.setOnClickListener(v -> {
                Bundle args = new Bundle();
                args.putLong("dialog_id", message.getDialogId());
                args.putInt("message_id", message.getRealId());
                presentFragment(new ChatActivity(args));
            });
        }

        @Override
        public int getItemCount() {
            return controller == null ? 0 : controller.getMessages().size();
        }
    }

    private static final class FeedHolder extends RecyclerView.ViewHolder {
        final TextView text;
        FeedHolder(@NonNull View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }
    }
}
