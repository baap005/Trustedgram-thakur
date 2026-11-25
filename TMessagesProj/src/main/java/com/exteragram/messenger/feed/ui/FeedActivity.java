/*
 * Derived from exteraGram feed architecture.
 * Copyright © exteraGram, 2022-2026. GNU GPL v3 or later; see repository
 * ADDITIONAL_TERMS.md and THIRD_PARTY_NOTICES.md.
 * TrustedGram adaptation: basic native feed host for Telegram Android 13.1.1.
 */
package com.exteragram.messenger.feed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.feed.FeedController;
import com.exteragram.messenger.feed.FeedConfig;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Feed tab host. FeedController builds a merged, locally cached timeline from
 * subscribed channels; tapping a post opens its original channel message.
 */
public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private FeedController controller;
    private RecyclerView listView;
    private LinearLayout categoryStrip;
    private FeedAdapter adapter;
    private int classGuid;
    private int loadIndex;
    private boolean loadingRequested;
    private final Runnable loadNewPosts = () -> {
        if (controller != null && controller.hasResumedUi()) {
            controller.loadNewer(classGuid, loadIndex++);
        }
    };

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) listView.smoothScrollToPosition(0);
    }

    @Override
    public boolean onFragmentCreate() {
        controller = FeedController.getInstance(currentAccount);
        controller.activateSelectedTimeline();
        classGuid = org.telegram.tgnet.ConnectionsManager.generateClassGuid();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagesDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
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
        header.addView(manage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP));

        HorizontalScrollView categoriesScroll = new HorizontalScrollView(context);
        categoriesScroll.setHorizontalScrollBarEnabled(false);
        categoryStrip = new LinearLayout(context);
        categoryStrip.setOrientation(LinearLayout.HORIZONTAL);
        categoriesScroll.addView(categoryStrip, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
            addCategoryButton(category.getTitle(), category.getId(), config.getSelectedCategoryId() == category.getId());
        }
        addCreateCategoryButton();
    }

    private void addCreateCategoryButton() {
        TextView add = new TextView(getContext());
        add.setText("+");
        add.setTextSize(20);
        add.setGravity(Gravity.CENTER);
        add.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        add.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.getColor(Theme.key_windowBackgroundWhite)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(34));
        categoryStrip.addView(add, lp);
        add.setOnClickListener(v -> {
            EditText input = new EditText(getContext());
            input.setSingleLine(true);
            input.setHint("Category name");
            new AlertDialog.Builder(getContext())
                    .setTitle("New feed category")
                    .setView(input)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Create", (dialog, which) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) return;
                        controller.loadChannels((channels, includedCount, failed, snapshot) -> {
                            if (failed || channels == null) return;
                            java.util.HashSet<Long> ids = new java.util.HashSet<>();
                            FeedConfig cfg = FeedConfig.getInstance(currentAccount);
                            for (org.telegram.tgnet.TLRPC.Chat chat : channels) {
                                long dialogId = -chat.id;
                                if (!cfg.isExcluded(dialogId)) ids.add(dialogId);
                            }
                            FeedConfig.Category category = cfg.saveCategory(0, name, 0, ids);
                            controller.selectCategory(category.getId());
                            updateCategoryTabs();
                            refresh();
                        });
                    })
                    .show();
        });
    }

    private void addCategoryButton(String label, int categoryId, boolean selected) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(7), dp(14), dp(7));
        button.setTextColor(Theme.getColor(selected ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteBlackText));
        button.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.getColor(selected ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(7);
        categoryStrip.addView(button, lp);
        button.setOnClickListener(v -> {
            if (controller != null && controller.selectCategory(categoryId)) {
                updateCategoryTabs();
                refresh();
            }
        });
        if (categoryId > 0) {
            button.setOnLongClickListener(v -> {
                new AlertDialog.Builder(getContext())
                        .setTitle("Delete category?")
                        .setMessage("This removes the category only. Your channel subscriptions stay unchanged.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (dialog, which) -> {
                            FeedConfig.getInstance(currentAccount).deleteCategory(categoryId);
                            controller.applyConfigChange(null);
                            updateCategoryTabs();
                            refresh();
                        })
                        .show();
                return true;
            });
        }
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
        if (id == NotificationCenter.didReceiveNewMessages) {
            if (args.length > 2 && !(Boolean) args[2] && controller != null && controller.isIncludedChannelPost((Long) args[0])) {
                AndroidUtilities.cancelRunOnUIThread(loadNewPosts);
                AndroidUtilities.runOnUIThread(loadNewPosts, 1000);
            }
        } else if (id == NotificationCenter.feedNeedReload) {
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
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        AndroidUtilities.cancelRunOnUIThread(loadNewPosts);
        if (controller != null) {
            controller.setUiResumed(false);
            controller.setUiActive(false);
        }
        super.onFragmentDestroy();
    }

    private void openMessage(MessageObject message) {
        Bundle args = new Bundle();
        args.putLong("dialog_id", message.getDialogId());
        args.putInt("message_id", message.getRealId());
        presentFragment(new ChatActivity(args));
    }

    private void showPostActions(MessageObject message) {
        ArrayList<String> actions = new ArrayList<>();
        actions.add("Open in chat");
        String postText = message.messageOwner != null && message.messageOwner.message != null ? message.messageOwner.message : "";
        if (!postText.isEmpty()) actions.add("Copy text");
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat((int) -message.getDialogId());
        String publicLink = FeedPostActions.getPublicPostLink(chat, message.getRealId());
        if (publicLink != null) actions.add("Copy link");
        String[] items = actions.toArray(new String[0]);
        new AlertDialog.Builder(getContext()).setItems(items, (dialog, which) -> {
            String action = items[which];
            if ("Open in chat".equals(action)) {
                openMessage(message);
            } else if ("Copy text".equals(action)) {
                ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Feed post", postText));
            } else if ("Copy link".equals(action) && publicLink != null) {
                ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Feed post link", publicLink));
            }
        }).show();
    }

    private final class FeedAdapter extends RecyclerView.Adapter<FeedHolder> {
        private final Context context;
        FeedAdapter(Context context) { this.context = context; }

        @NonNull
        @Override
        @NonNull
        @Override
        public FeedHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
            cell.setResourcesProvider(getResourceProvider());
            cell.shouldCheckVisibleOnScreen = false;
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public void didLongPress(ChatMessageCell pressedCell, float x, float y) {
                    MessageObject message = pressedCell.getMessageObject();
                    if (message != null) showPostActions(message);
                }

                @Override
                public void didPressChannelAvatar(ChatMessageCell pressedCell, TLRPC.Chat chat, int postId, float touchX, float touchY, boolean asForward) {
                    MessageObject message = pressedCell.getMessageObject();
                    if (message != null) openMessage(message);
                }

                @Override
                public void didPressOther(ChatMessageCell pressedCell, float otherX, float otherY) {
                    MessageObject message = pressedCell.getMessageObject();
                    if (message != null) openMessage(message);
                }
            });
            return new FeedHolder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull FeedHolder holder, int position) {
            ArrayList<MessageObject> messages = controller.getStore().getVisibleMessages();
            if (position < 0 || position >= messages.size()) return;
            MessageObject message = messages.get(position);
            holder.cell.setMessageObject(message, null, true, true, false);
        }

        @Override
        public int getItemCount() {
            return controller == null ? 0 : controller.getStore().getVisibleMessages().size();
        }
    }

    private static final class FeedHolder extends RecyclerView.ViewHolder {
        final ChatMessageCell cell;
        FeedHolder(@NonNull ChatMessageCell itemView) {
            super(itemView);
            cell = itemView;
        }
    }
}
