package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.TrustedConfig;
import org.telegram.messenger.TrustedFeed;
import org.telegram.messenger.TrustedGhost;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * TrustedGram Feed: the newest posts of your channels in one chat-style list.
 * - Floating top bar (back, feed title with unread counter, mark-read and settings), like the exteraGram feed.
 * - Posts are drawn with Telegram's own message cell: formatting, premium emoji, quotes, reactions, media, link
 *   previews and inline buttons all look and work like in the channel.
 * - Albums are merged into one post (photos in one grid, one caption), exactly like in the channel.
 * - Long press a post: Open post, Open channel, Copy text, Copy link, Forward.
 */
public class TGramFeedActivity extends BaseFragment {

    private static final int PER_CHANNEL = 10;
    private static final int MAX_CHANNELS = 30;
    private static final int MAX_POSTS = 80;

    private static class Post {
        final ArrayList<MessageObject> messages = new ArrayList<>(); // oldest first
        MessageObject.GroupedMessages group;
        TLRPC.Chat chat;
        int date;

        MessageObject first() {
            return messages.get(0);
        }
    }

    private final ArrayList<Post> posts = new ArrayList<>();
    private final ArrayList<TLRPC.Chat> loadedChannels = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;
    private TextView statusView;
    private TextView subtitleView;
    private int loadGeneration;
    private int pending;

    // ------------------------------------------------------------------ screen

    private int barColor() {
        return Theme.getColor(Theme.key_chat_serviceBackground);
    }

    private int barText() {
        return Theme.getColor(Theme.key_chat_serviceText);
    }

    private View pill(Context context, View content) {
        FrameLayout f = new FrameLayout(context);
        GradientDrawable g = new GradientDrawable();
        g.setColor(barColor());
        g.setCornerRadius(dp(24));
        f.setBackground(g);
        f.addView(content, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        return f;
    }

    private ImageView barIcon(Context context, int res) {
        ImageView v = new ImageView(context);
        v.setImageResource(res);
        v.setColorFilter(barText());
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
        return v;
    }

    @Override
    public View createView(Context context) {
        // we draw our own floating top bar
        actionBar.setAddToContainer(false);
        actionBar.setVisibility(View.GONE);

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

        final int top = AndroidUtilities.statusBarHeight + dp(62);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(0, top, 0, dp(12));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < posts.size()) openPost(posts.get(position));
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        statusView = new TextView(context);
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(barText());
        statusView.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(barColor());
        sg.setCornerRadius(dp(16));
        statusView.setBackground(sg);
        frame.addView(statusView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        // ---- floating bar: [back]  [icon + title/subtitle]            [read] [settings]
        int barTop = AndroidUtilities.statusBarHeight + dp(8);

        ImageView back = barIcon(context, R.drawable.ic_ab_back);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(barColor());
        FrameLayout backWrap = new FrameLayout(context);
        backWrap.setBackground(bg);
        backWrap.addView(back, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        backWrap.setOnClickListener(v -> finishFragment());
        FrameLayout.LayoutParams blp = LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.LEFT, 8, 0, 0, 0);
        blp.topMargin = barTop;
        frame.addView(backWrap, blp);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout iconCircle = new FrameLayout(context);
        GradientDrawable ig = new GradientDrawable();
        ig.setShape(GradientDrawable.OVAL);
        ig.setColor(0xFF2E8BEF);
        iconCircle.setBackground(ig);
        ImageView feedIcon = new ImageView(context);
        feedIcon.setImageResource(R.drawable.msg_channel);
        feedIcon.setColorFilter(0xFFFFFFFF);
        iconCircle.addView(feedIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        titleRow.addView(iconCircle, LayoutHelper.createLinear(36, 36, 4, 0, 0, 0));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("Feed");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(barText());
        titles.addView(title);
        subtitleView = new TextView(context);
        subtitleView.setTextSize(13);
        subtitleView.setTextColor(barText() & 0xCCFFFFFF);
        titles.addView(subtitleView);
        titleRow.addView(titles, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 16, 0));
        View titlePill = pill(context, titleRow);
        titlePill.setOnClickListener(v -> load());
        FrameLayout.LayoutParams tlp = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.TOP | Gravity.LEFT, 60, 0, 0, 0);
        tlp.topMargin = barTop;
        frame.addView(titlePill, tlp);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        ImageView read = barIcon(context, R.drawable.msg_markread);
        read.setOnClickListener(v -> confirmMarkAllRead());
        actions.addView(read, LayoutHelper.createLinear(44, 44));
        ImageView settings = barIcon(context, R.drawable.msg_customize);
        settings.setOnClickListener(v -> presentFragment(new TGramFeedChannelsActivity()));
        actions.addView(settings, LayoutHelper.createLinear(44, 44));
        View actionPill = pill(context, actions);
        FrameLayout.LayoutParams alp = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.TOP | Gravity.RIGHT, 0, 0, 8, 0);
        alp.topMargin = barTop;
        frame.addView(actionPill, alp);

        fragmentView = frame;
        load();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (TrustedFeed.dirty) load();
        updateSubtitle();
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
        for (final TLRPC.Chat chat : new ArrayList<>(loadedChannels)) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = MessagesController.getInstance(account).getInputPeer(-chat.id);
            req.limit = PER_CHANNEL;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (generation != loadGeneration) return;
                if (response instanceof TLRPC.messages_Messages) {
                    addHistory(account, chat, (TLRPC.messages_Messages) response);
                }
                if (--pending == 0) render();
            }));
        }
    }

    private void addHistory(int account, TLRPC.Chat chat, TLRPC.messages_Messages res) {
        MessagesController.getInstance(account).putUsers(res.users, false);
        MessagesController.getInstance(account).putChats(res.chats, false);
        HashMap<Long, TLRPC.User> users = new HashMap<>();
        for (TLRPC.User u : res.users) users.put(u.id, u);
        HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
        for (TLRPC.Chat c : res.chats) chats.put(c.id, c);

        HashMap<Long, Post> albums = new HashMap<>();
        ArrayList<Post> channelPosts = new ArrayList<>();
        for (TLRPC.Message m : res.messages) {
            if (m instanceof TLRPC.TL_messageEmpty || m instanceof TLRPC.TL_messageService) continue;
            MessageObject mo = new MessageObject(account, m, users, chats, true, true);
            Post p = m.grouped_id != 0 ? albums.get(m.grouped_id) : null;
            if (p == null) {
                p = new Post();
                p.chat = chat;
                p.date = m.date;
                channelPosts.add(p);
                if (m.grouped_id != 0) albums.put(m.grouped_id, p);
            }
            p.messages.add(mo);
        }
        for (Post p : channelPosts) {
            if (p.messages.size() > 1) {
                // album: oldest photo first, one shared layout
                Collections.sort(p.messages, (a, b) -> a.getId() - b.getId());
                MessageObject.GroupedMessages g = new MessageObject.GroupedMessages();
                g.groupId = p.first().messageOwner.grouped_id;
                g.messages.addAll(p.messages);
                g.calculate();
                p.group = g;
                // an album shows the time of its newest part
                p.date = p.messages.get(p.messages.size() - 1).messageOwner.date;
            }
            posts.add(p);
        }
    }

    private void render() {
        Collections.sort(posts, (a, b) -> b.date - a.date);
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
        if (subtitleView == null) return;
        String text = loadedChannels.size() + (loadedChannels.size() == 1 ? " channel" : " channels");
        if (TrustedConfig.isFeedUnreadCounter()) {
            int unread = countUnread();
            if (unread > 0) text += " \u2022 " + unread + " unread";
        }
        subtitleView.setText(text);
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

    // ------------------------------------------------------------------ actions on a post

    private void openPost(Post p) {
        if (p == null) return;
        Bundle args = new Bundle();
        args.putLong("chat_id", p.chat.id);
        args.putInt("message_id", p.first().getId());
        presentFragment(new ChatActivity(args));
    }

    private void openChannel(Post p) {
        Bundle args = new Bundle();
        args.putLong("chat_id", p.chat.id);
        presentFragment(new ChatActivity(args));
    }

    private static CharSequence textOf(Post p) {
        for (MessageObject mo : p.messages) {
            CharSequence t = null;
            if (mo.isPhoto() || mo.isVideo() || mo.isGif() || mo.getDocument() != null) t = mo.caption;
            else t = mo.messageText;
            if (!TextUtils.isEmpty(t)) return t;
        }
        return null;
    }

    private static String linkOf(Post p) {
        String username = ChatObject.getPublicUsername(p.chat);
        int id = p.first().getId();
        return username != null ? "https://t.me/" + username + "/" + id : "https://t.me/c/" + p.chat.id + "/" + id;
    }

    private void showPostActions(final Post p) {
        if (p == null || getParentActivity() == null) return;
        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayList<Integer> icons = new ArrayList<>();
        final ArrayList<Integer> actions = new ArrayList<>();
        final CharSequence text = textOf(p);
        labels.add("Open post"); icons.add(R.drawable.msg_openin); actions.add(0);
        labels.add("Open channel"); icons.add(R.drawable.msg_channel); actions.add(1);
        if (!TextUtils.isEmpty(text)) { labels.add("Copy text"); icons.add(R.drawable.msg_copy); actions.add(2); }
        labels.add("Copy link"); icons.add(R.drawable.msg_link); actions.add(3);
        labels.add("Forward"); icons.add(R.drawable.msg_forward); actions.add(4);
        int[] iconArray = new int[icons.size()];
        for (int i = 0; i < iconArray.length; i++) iconArray[i] = icons.get(i);
        BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
        builder.setItems(labels.toArray(new CharSequence[0]), iconArray, (DialogInterface dialog, int which) -> {
            int a = actions.get(which);
            if (a == 0) openPost(p);
            else if (a == 1) openChannel(p);
            else if (a == 2) {
                AndroidUtilities.addToClipboard(text);
                toast("Text copied");
            } else if (a == 3) {
                AndroidUtilities.addToClipboard(linkOf(p));
                toast("Link copied");
            } else if (a == 4) forward(p);
        });
        showDialog(builder.create());
    }

    private void toast(String s) {
        if (getParentActivity() != null) Toast.makeText(getParentActivity(), s, Toast.LENGTH_SHORT).show();
    }

    private void forward(final Post p) {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("canSelectTopics", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            for (MessagesStorage.TopicKey key : dids) {
                sendForward(currentAccount, new ArrayList<>(p.messages), key.dialogId);
            }
            fragment.finishFragment();
            toast("Forwarded");
            return true;
        });
        presentFragment(picker);
    }

    /** Finds SendMessagesHelper's "forward these messages" method whatever its exact parameter list is in this build. */
    private static void sendForward(int account, ArrayList<MessageObject> messages, long dialogId) {
        try {
            Method best = null;
            for (Method m : SendMessagesHelper.class.getMethods()) {
                if (!m.getName().equals("sendMessage")) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length < 6 || t[0] != ArrayList.class || t[1] != long.class) continue;
                if (best == null || t.length < best.getParameterTypes().length) best = m;
            }
            if (best == null) return;
            Class<?>[] t = best.getParameterTypes();
            Object[] args = new Object[t.length];
            int booleans = 0;
            for (int i = 0; i < t.length; i++) {
                if (i == 0) args[i] = messages;
                else if (i == 1) args[i] = dialogId;
                else if (t[i] == boolean.class) args[i] = (++booleans == 3); // forwardFromMyName=false, hideCaption=false, notify=true
                else if (t[i] == int.class) args[i] = 0;
                else if (t[i] == long.class) args[i] = 0L;
                else args[i] = null;
            }
            best.invoke(SendMessagesHelper.getInstance(account), args);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    private void handleLink(String link) {
        if (link == null || getParentActivity() == null) return;
        if (link.startsWith("@")) {
            MessagesController.getInstance(currentAccount).openByUserName(link.substring(1), this, 0);
        } else if (link.startsWith("#") || link.startsWith("$") || link.startsWith("/")) {
            AndroidUtilities.addToClipboard(link);
            toast("Copied " + link);
        } else {
            Browser.openUrl(getParentActivity(), link);
        }
    }

    // ------------------------------------------------------------------ list

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
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
        private final LinearLayout cellsBox;
        private final ArrayList<ChatMessageCell> cells = new ArrayList<>();
        private final ChatMessageCell.ChatMessageCellDelegate delegate;
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
            header.setOnClickListener(v -> openChannel(current));
            header.setOnLongClickListener(v -> {
                showPostActions(current);
                return true;
            });
            addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            cellsBox = new LinearLayout(context);
            cellsBox.setOrientation(VERTICAL);
            addView(cellsBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            delegate = new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return true;
                }

                @Override
                public void didPressUrl(ChatMessageCell cell, CharacterStyle url, boolean longPress) {
                    if (longPress) {
                        showPostActions(current);
                        return;
                    }
                    if (url instanceof URLSpan) handleLink(((URLSpan) url).getURL());
                }

                public void didLongPress(ChatMessageCell cell, float x, float y) {
                    showPostActions(current);
                }

                public void didPressImage(ChatMessageCell cell, float x, float y, boolean fullPreview) {
                    openPost(current);
                }

                public void didPressImage(ChatMessageCell cell, float x, float y) {
                    openPost(current);
                }

                public void didPressSideButton(ChatMessageCell cell) {
                    if (current != null) forward(current);
                }
            };
        }

        private ChatMessageCell cellAt(int index) {
            while (cells.size() <= index) {
                ChatMessageCell cell = new ChatMessageCell(getContext(), currentAccount);
                cell.setDelegate(delegate);
                cells.add(cell);
                cellsBox.addView(cell, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            }
            return cells.get(index);
        }

        void bind(Post post) {
            current = post;
            int side = TrustedConfig.isFeedWide() ? 0 : dp(14);
            setPadding(side, 0, side, 0);
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(post.chat);
            avatar.setForUserOrChat(post.chat, avatarDrawable);
            nameView.setText(post.chat.title);
            dateView.setText(LocaleController.formatDateChat(post.date));
            int n = post.messages.size();
            for (int i = 0; i < cells.size() || i < n; i++) {
                if (i >= n) {
                    cells.get(i).setVisibility(View.GONE);
                    continue;
                }
                ChatMessageCell cell = cellAt(i);
                cell.setVisibility(View.VISIBLE);
                cell.isChat = false;
                cell.setFullyDraw(true);
                cell.setMessageObject(post.messages.get(i), post.group, post.group != null && i < n - 1, post.group != null && i > 0, false);
            }
        }
    }
}
