package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.TrustedConfig;
import org.telegram.messenger.TrustedFeed;
import org.telegram.messenger.TrustedGhost;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * Combined feed of the newest posts from the joined channels the user picked. Posts are drawn with Telegram's own
 * chat message cell, so premium emojis, text formatting, quotes, links, reactions and media look exactly like in the channel.
 */
public class TGramFeedActivity extends BaseFragment {

    private static final int PER_CHANNEL = 6;
    private static final int MAX_CHANNELS = 30;
    private static final int MAX_POSTS = 80;
    private static final int MENU_READ = 1, MENU_SETTINGS = 2, MENU_REFRESH = 3;

    private static class Post {
        MessageObject message;
        TLRPC.Chat chat;
    }

    private final ArrayList<Post> posts = new ArrayList<>();
    private final ArrayList<TLRPC.Chat> loadedChannels = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;
    private TextView statusView;
    private int loadGeneration;
    private int pending;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Feed");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_READ, R.drawable.msg_markread);
        menu.addItem(MENU_REFRESH, R.drawable.msg_retry);
        menu.addItem(MENU_SETTINGS, R.drawable.msg_customize);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == MENU_SETTINGS) presentFragment(new TGramFeedChannelsActivity());
                else if (id == MENU_REFRESH) load();
                else if (id == MENU_READ) confirmMarkAllRead();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        Drawable wallpaper = null;
        try {
            Drawable cached = Theme.getCachedWallpaperNonBlocking();
            if (cached != null && cached.getConstantState() != null) {
                wallpaper = cached.getConstantState().newDrawable().mutate();
            }
        } catch (Throwable ignore) {
        }
        frame.setBackground(wallpaper != null ? wallpaper : new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray)));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(6), 0, dp(12));
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        statusView = new TextView(context);
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusView.setPadding(dp(32), 0, dp(32), 0);
        frame.addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = frame;
        load();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (TrustedFeed.dirty) load();
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        TrustedFeed.dirty = false;
        final int account = currentAccount;
        loadedChannels.clear();
        for (TLRPC.Chat c : TrustedFeed.getChannels(account)) {
            if (TrustedFeed.isIncluded(c.id) && loadedChannels.size() < MAX_CHANNELS) loadedChannels.add(c);
        }
        final int generation = ++loadGeneration;
        posts.clear();
        adapter.notifyDataSetChanged();
        updateSubtitle();
        if (loadedChannels.isEmpty()) {
            statusView.setText("Your feed is empty.\nTap the settings icon at the top to choose channels.");
            statusView.setVisibility(View.VISIBLE);
            return;
        }
        statusView.setText("Loading feed...");
        statusView.setVisibility(View.VISIBLE);
        pending = loadedChannels.size();
        for (TLRPC.Chat chat : new ArrayList<>(loadedChannels)) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = MessagesController.getInstance(account).getInputPeer(-chat.id);
            req.limit = PER_CHANNEL;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (generation != loadGeneration) return;
                if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    MessagesController.getInstance(account).putUsers(res.users, false);
                    MessagesController.getInstance(account).putChats(res.chats, false);
                    HashMap<Long, TLRPC.User> users = new HashMap<>();
                    for (TLRPC.User u : res.users) users.put(u.id, u);
                    HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
                    for (TLRPC.Chat c : res.chats) chats.put(c.id, c);
                    for (TLRPC.Message m : res.messages) {
                        if (m instanceof TLRPC.TL_messageEmpty || m instanceof TLRPC.TL_messageService) continue;
                        Post p = new Post();
                        p.message = new MessageObject(account, m, users, chats, true, true);
                        p.chat = chat;
                        posts.add(p);
                    }
                }
                if (--pending == 0) render();
            }));
        }
    }

    private void render() {
        Collections.sort(posts, (a, b) -> b.message.messageOwner.date - a.message.messageOwner.date);
        while (posts.size() > MAX_POSTS) posts.remove(posts.size() - 1);
        adapter.notifyDataSetChanged();
        statusView.setVisibility(posts.isEmpty() ? View.VISIBLE : View.GONE);
        if (posts.isEmpty()) statusView.setText("No posts found.");
        updateSubtitle();
    }

    private int countUnread() {
        int total = 0;
        MessagesController mc = MessagesController.getInstance(currentAccount);
        for (TLRPC.Chat c : loadedChannels) {
            TLRPC.Dialog d = mc.dialogs_dict.get(-c.id);
            if (d != null) total += d.unread_count;
        }
        return total;
    }

    private void updateSubtitle() {
        String text = loadedChannels.size() + (loadedChannels.size() == 1 ? " channel" : " channels");
        if (TrustedConfig.isFeedUnreadCounter()) {
            int unread = countUnread();
            if (unread > 0) text += " \u2022 " + unread + " unread";
        }
        actionBar.setSubtitle(text);
    }

    // ------------------------------------------------------------------ mark all read

    private void confirmMarkAllRead() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Mark All as Read");
        b.setMessage("Mark all posts in your feed channels as read? This will also mark them as read in the original channels.");
        b.setPositiveButton("Mark as read", (d, w) -> markAllRead());
        b.setNegativeButton("Cancel", null);
        showDialog(b.create());
    }

    private void markAllRead() {
        MessagesController mc = MessagesController.getInstance(currentAccount);
        for (TLRPC.Chat c : loadedChannels) {
            TLRPC.Dialog d = mc.dialogs_dict.get(-c.id);
            if (d == null || d.unread_count <= 0) continue;
            TrustedGhost.allowNextRead();
            mc.markDialogAsRead(-c.id, d.top_message, d.top_message, d.last_message_date, false, 0, d.unread_count, true, 0);
        }
        AndroidUtilities.runOnUIThread(this::updateSubtitle, 600);
    }

    // ------------------------------------------------------------------ list

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return posts.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            PostView view = new PostView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((PostView) holder.itemView).bind(posts.get(position));
        }
    }

    private class PostView extends LinearLayout {
        private final BackupImageView avatar;
        private final TextView nameView;
        private final TextView dateView;
        private final ChatMessageCell cell;
        private Post current;

        PostView(Context context) {
            super(context);
            setOrientation(VERTICAL);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(16), dp(8), dp(16), dp(2));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(16));
            header.addView(avatar, LayoutHelper.createLinear(32, 32));
            LinearLayout names = new LinearLayout(context);
            names.setOrientation(VERTICAL);
            nameView = new TextView(context);
            nameView.setTextSize(15);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn));
            names.addView(nameView);
            dateView = new TextView(context);
            dateView.setTextSize(12);
            dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            names.addView(dateView);
            header.addView(names, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10, 0, 0, 0));
            header.setOnClickListener(v -> openPost());
            addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            cell = new ChatMessageCell(context, currentAccount);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return true;
                }

                @Override
                public void didPressUrl(ChatMessageCell cell, CharacterStyle url, boolean longPress) {
                    if (longPress || !(url instanceof URLSpan) || getParentActivity() == null) return;
                    String link = ((URLSpan) url).getURL();
                    if (link != null) Browser.openUrl(getParentActivity(), link);
                }
            });
            addView(cell, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        void bind(Post post) {
            current = post;
            int side = TrustedConfig.isFeedWide() ? 0 : dp(14);
            setPadding(side, 0, side, 0);
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(post.chat);
            avatar.setForUserOrChat(post.chat, avatarDrawable);
            nameView.setText(post.chat.title);
            dateView.setText(LocaleController.formatDateChat(post.message.messageOwner.date));
            cell.isChat = false;
            cell.setFullyDraw(true);
            cell.setMessageObject(post.message, null, false, false, false);
        }

        private void openPost() {
            if (current == null) return;
            Bundle args = new Bundle();
            args.putLong("chat_id", current.chat.id);
            args.putInt("message_id", current.message.getId());
            presentFragment(new ChatActivity(args));
        }
    }
}
