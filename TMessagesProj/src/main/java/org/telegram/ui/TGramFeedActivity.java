package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
import org.telegram.messenger.MediaController;
import org.telegram.messenger.utils.tlutils.TLKeyboardHelper;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.ChatUnreadCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Calendar;
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

    private static final int PER_CHANNEL = 30;
    private static final int MAX_CHANNELS = 30;
    private static final int MAX_POSTS = 120;
    private static final int TYPE_POST = 0, TYPE_DATE = 1, TYPE_UNREAD = 2;

    private static class Post {
        final ArrayList<MessageObject> messages = new ArrayList<>(); // oldest first
        MessageObject.GroupedMessages group;
        TLRPC.Chat chat;
        int date;

        boolean unread;

        MessageObject first() {
            return messages.get(0);
        }

        MessageObject last() {
            return messages.get(messages.size() - 1);
        }
    }

    /** One row of the feed: a post, a date tile (Today / Yesterday / date) or the "Unread messages" tile. */
    private static class Item {
        int type;
        Post post;
        MessageObject dateObject;
    }

    private final ArrayList<Post> posts = new ArrayList<>();
    private final ArrayList<Item> items = new ArrayList<>();
    private final HashMap<Long, Integer> readMax = new HashMap<>();
    private final HashMap<Long, Integer> markedUpTo = new HashMap<>();
    private LinearLayoutManager layoutManager;
    private FrameLayout downButton;
    private TextView downCounter;
    private boolean downVisible;
    private int unreadItemIndex = -1;
    private boolean initialScrollDone;
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
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(0, top, 0, dp(12));
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateDownButton(true);
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    markSeenPosts();
                }
            }
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
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        // keep the title pill clear of the buttons on both sides so it can sit exactly in the middle
        final int textMax = AndroidUtilities.displaySize.x - 2 * dp(96) - dp(66);
        subtitleView.setMaxWidth(Math.max(dp(80), textMax));
        title.setSingleLine(true);
        title.setMaxWidth(Math.max(dp(80), textMax));
        titles.addView(subtitleView);
        titleRow.addView(titles, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 16, 0));
        View titlePill = pill(context, titleRow);
        titlePill.setOnClickListener(v -> load());
        FrameLayout.LayoutParams tlp = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tlp.topMargin = barTop;
        frame.addView(titlePill, tlp);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        ImageView read = barIcon(context, R.drawable.msg_markread);
        read.setOnClickListener(v -> confirmMarkAllRead());
        actions.addView(read, LayoutHelper.createLinear(40, 44));
        ImageView settings = barIcon(context, R.drawable.msg_customize);
        settings.setOnClickListener(v -> presentFragment(new TGramFeedChannelsActivity()));
        actions.addView(settings, LayoutHelper.createLinear(40, 44));
        View actionPill = pill(context, actions);
        FrameLayout.LayoutParams alp = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.TOP | Gravity.RIGHT, 0, 0, 8, 0);
        alp.topMargin = barTop;
        frame.addView(actionPill, alp);

        // floating "jump to the bottom" arrow, bottom right, like in channels
        downButton = new FrameLayout(context);
        View arrow = new DownArrowView(context);
        downButton.addView(arrow, LayoutHelper.createFrame(46, 46, Gravity.CENTER));
        downCounter = new TextView(context);
        downCounter.setTextSize(12);
        downCounter.setTypeface(Typeface.DEFAULT_BOLD);
        downCounter.setGravity(Gravity.CENTER);
        downCounter.setTextColor(Theme.getColor(Theme.key_chat_goDownButtonCounter));
        downCounter.setPadding(dp(5), 0, dp(5), 0);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Theme.getColor(Theme.key_chat_goDownButtonCounterBackground));
        cg.setCornerRadius(dp(10));
        downCounter.setBackground(cg);
        downCounter.setVisibility(View.GONE);
        downButton.addView(downCounter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 20, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        downButton.setVisibility(View.GONE);
        downButton.setAlpha(0f);
        downButton.setOnClickListener(v -> jumpToBottom());
        frame.addView(downButton, LayoutHelper.createFrame(56, 62, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 14));

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
        MessagesController readController = MessagesController.getInstance(account);
        readMax.clear();
        markedUpTo.clear();
        for (TLRPC.Chat c : loadedChannels) {
            TLRPC.Dialog d = readController.dialogs_dict.get(-c.id);
            readMax.put(c.id, d != null ? d.read_inbox_max_id : Integer.MAX_VALUE);
        }
        initialScrollDone = false;
        unreadItemIndex = -1;
        posts.clear();
        items.clear();
        adapter.notifyDataSetChanged();
        updateDownButton(false);
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
        // the oldest album of a full page may be cut in half: drop it instead of showing half an album
        if (res.messages.size() >= PER_CHANNEL && !channelPosts.isEmpty()) {
            Post oldest = channelPosts.get(channelPosts.size() - 1);
            if (oldest.first().messageOwner.grouped_id != 0) channelPosts.remove(oldest);
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
        // oldest first, newest at the bottom, like a real channel
        Collections.sort(posts, (a, b) -> a.date != b.date ? a.date - b.date : a.first().getId() - b.first().getId());
        while (posts.size() > MAX_POSTS) posts.remove(0);

        items.clear();
        unreadItemIndex = -1;
        int lastDay = -1;
        int unreadPosts = 0;
        Calendar cal = Calendar.getInstance();
        for (Post p : posts) {
            Integer rm = readMax.get(p.chat.id);
            p.unread = rm != null && p.last().getId() > rm;
            cal.setTimeInMillis(p.date * 1000L);
            int day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
            if (day != lastDay) {
                lastDay = day;
                Item d = new Item();
                d.type = TYPE_DATE;
                d.dateObject = createDateObject(p.date);
                items.add(d);
            }
            if (p.unread) {
                unreadPosts++;
                if (unreadItemIndex < 0) {
                    Item u = new Item();
                    u.type = TYPE_UNREAD;
                    unreadItemIndex = items.size();
                    items.add(u);
                }
            }
            Item it = new Item();
            it.type = TYPE_POST;
            it.post = p;
            items.add(it);
        }
        adapter.notifyDataSetChanged();
        statusView.setVisibility(posts.isEmpty() ? View.VISIBLE : View.GONE);
        if (posts.isEmpty()) statusView.setText("No posts found.");
        updateSubtitle();
        scrollToStart();
    }

    private String dateLabel(int date) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(date * 1000L);
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "Today";
        }
        if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }
        return LocaleController.formatDateChat(date);
    }

    private MessageObject createDateObject(int date) {
        TLRPC.Message dateMsg = new TLRPC.TL_message();
        dateMsg.message = dateLabel(date);
        dateMsg.id = 0;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(date * 1000L);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        dateMsg.date = (int) (calendar.getTimeInMillis() / 1000);
        MessageObject dateObj = new MessageObject(currentAccount, dateMsg, false, false);
        dateObj.type = MessageObject.TYPE_DATE;
        dateObj.contentType = 1;
        dateObj.isDateObject = true;
        return dateObj;
    }

    /** Opens the feed where the user stopped reading (at the "Unread messages" tile), or at the newest post when all is read. */
    private void scrollToStart() {
        if (items.isEmpty() || initialScrollDone) return;
        initialScrollDone = true;
        listView.post(() -> {
            if (items.isEmpty()) return;
            if (unreadItemIndex >= 0) {
                layoutManager.scrollToPositionWithOffset(unreadItemIndex, dp(8));
                listView.postDelayed(() -> {
                    updateDownButton(false);
                    markSeenPosts();
                }, 150);
            } else {
                alignLastToBottom();
            }
        });
    }

    /** Puts the newest post with its bottom edge at the bottom of the list. */
    private void alignLastToBottom() {
        if (items.isEmpty()) return;
        final int target = items.size() - 1;
        layoutManager.scrollToPositionWithOffset(target, 0);
        listView.post(() -> {
            View v = layoutManager.findViewByPosition(target);
            if (v != null) {
                int avail = listView.getHeight() - listView.getPaddingTop() - listView.getPaddingBottom();
                layoutManager.scrollToPositionWithOffset(target, avail - v.getHeight());
            }
            listView.postDelayed(() -> {
                updateDownButton(false);
                markSeenPosts();
            }, 150);
        });
    }

    // ------------------------------------------------------------------ read state + jump arrow

    /** Posts that scrolled into view count as read, exactly like in a channel (Ghost mode still blocks the read receipt). */
    private void markSeenPosts() {
        if (layoutManager == null || items.isEmpty()) return;
        int last = layoutManager.findLastVisibleItemPosition();
        if (last < 0) return;
        HashMap<Long, Integer> maxIds = new HashMap<>();
        HashMap<Long, Integer> counts = new HashMap<>();
        HashMap<Long, Integer> dates = new HashMap<>();
        for (int i = 0; i <= last && i < items.size(); i++) {
            Item it = items.get(i);
            if (it.type != TYPE_POST || !it.post.unread) continue;
            long cid = it.post.chat.id;
            int id = it.post.last().getId();
            Integer done = markedUpTo.get(cid);
            if (done != null && id <= done) continue;
            Integer cur = maxIds.get(cid);
            if (cur == null || id > cur) {
                maxIds.put(cid, id);
                dates.put(cid, it.post.date);
            }
            Integer n = counts.get(cid);
            counts.put(cid, (n == null ? 0 : n) + it.post.messages.size());
        }
        MessagesController mc = MessagesController.getInstance(currentAccount);
        for (Long cid : maxIds.keySet()) {
            int id = maxIds.get(cid);
            markedUpTo.put(cid, id);
            mc.markDialogAsRead(-cid, id, id, dates.get(cid), false, 0, counts.get(cid), true, 0);
        }
        if (!maxIds.isEmpty()) {
            AndroidUtilities.runOnUIThread(this::updateSubtitle, 600);
            updateDownButton(true);
        }
    }

    private int unreadBelow() {
        int last = layoutManager.findLastVisibleItemPosition();
        int total = 0;
        for (int i = Math.max(0, last + 1); i < items.size(); i++) {
            Item it = items.get(i);
            if (it.type != TYPE_POST || !it.post.unread) continue;
            Integer done = markedUpTo.get(it.post.chat.id);
            if (done != null && it.post.last().getId() <= done) continue;
            total++;
        }
        return total;
    }

    private void updateDownButton(boolean animated) {
        if (downButton == null || layoutManager == null) return;
        int remaining = listView.computeVerticalScrollRange() - listView.computeVerticalScrollOffset() - listView.computeVerticalScrollExtent();
        boolean show = !items.isEmpty() && remaining > dp(160);
        int below = show ? unreadBelow() : 0;
        if (below > 0) {
            downCounter.setText(String.valueOf(below));
            downCounter.setVisibility(View.VISIBLE);
        } else {
            downCounter.setVisibility(View.GONE);
        }
        if (show == downVisible) return;
        downVisible = show;
        downButton.animate().cancel();
        if (show) {
            downButton.setVisibility(View.VISIBLE);
            if (animated) {
                downButton.animate().alpha(1f).translationY(0).setDuration(180).start();
            } else {
                downButton.setAlpha(1f);
                downButton.setTranslationY(0);
            }
        } else if (animated) {
            downButton.animate().alpha(0f).translationY(dp(20)).setDuration(180).withEndAction(() -> {
                if (!downVisible) downButton.setVisibility(View.GONE);
            }).start();
        } else {
            downButton.setAlpha(0f);
            downButton.setVisibility(View.GONE);
        }
    }

    private void jumpToBottom() {
        if (items.isEmpty()) return;
        int target = items.size() - 1;
        int last = layoutManager.findLastVisibleItemPosition();
        if (target - last > 12) {
            alignLastToBottom();
        } else {
            listView.smoothScrollToPosition(target);
        }
    }

    /** Round button with a down chevron, drawn by hand. */
    private static class DownArrowView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path chevron = new Path();

        DownArrowView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(21);
            fill.setStyle(Paint.Style.FILL);
            fill.setShadowLayer(dp(3), 0, dp(1), 0x33000000);
            fill.setColor(Theme.getColor(Theme.key_chat_goDownButton));
            canvas.drawCircle(cx, cy, r, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            chevron.reset();
            chevron.moveTo(cx - dp(7), cy - dp(3));
            chevron.lineTo(cx, cy + dp(4));
            chevron.lineTo(cx + dp(7), cy - dp(3));
            canvas.drawPath(chevron, line);
        }
    }

    // ------------------------------------------------------------------ unread counter

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
            markedUpTo.put(c.id, d.top_message);
        }
        updateDownButton(true);
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
            return holder.getItemViewType() == TYPE_POST;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_DATE) {
                view = new ChatActionCell(parent.getContext());
            } else if (viewType == TYPE_UNREAD) {
                view = new ChatUnreadCell(parent.getContext(), null);
            } else {
                view = new PostView(parent.getContext());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);
            if (item.type == TYPE_DATE) {
                ((ChatActionCell) holder.itemView).setMessageObject(item.dateObject);
            } else if (item.type == TYPE_UNREAD) {
                ((ChatUnreadCell) holder.itemView).setText(LocaleController.getString(R.string.UnreadMessages));
            } else {
                ((PostView) holder.itemView).bind(item.post);
            }
        }
    }

    private void openPhotoViewer(ChatMessageCell cell) {
        MessageObject mo = cell.getMessageObject();
        if (mo == null || current(cell) == null || getParentActivity() == null) return;
        Post p = current(cell);
        PhotoViewer viewer = PhotoViewer.getInstance();
        viewer.setParentActivity(this);
        boolean opened;
        if (p.messages.size() > 1) {
            opened = viewer.openPhoto(new ArrayList<>(p.messages), Math.max(0, p.messages.indexOf(mo)), -p.chat.id, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
        } else {
            opened = viewer.openPhoto(mo, null, -p.chat.id, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
        }
        if (!opened) openPost(p);
    }

    private Post current(ChatMessageCell cell) {
        View parent = cell;
        while (parent != null && !(parent instanceof PostView)) {
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        }
        return parent != null ? ((PostView) parent).current : null;
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
                    openPhotoViewer(cell);
                }

                public void didPressImage(ChatMessageCell cell, float x, float y) {
                    openPhotoViewer(cell);
                }

                public void didPressSideButton(ChatMessageCell cell) {
                    if (current != null) forward(current);
                }

                // link preview / instant view button: open the page
                public void didPressInstantButton(ChatMessageCell cell, int type) {
                    MessageObject mo = cell.getMessageObject();
                    if (mo != null && mo.messageOwner != null && mo.messageOwner.media != null && mo.messageOwner.media.webpage != null && mo.messageOwner.media.webpage.url != null) {
                        Browser.openUrl(getParentActivity(), mo.messageOwner.media.webpage.url);
                    } else {
                        openPost(current);
                    }
                }

                public void didPressWebPage(ChatMessageCell cell, TLRPC.WebPage webpage, String url, boolean safe) {
                    Browser.openUrl(getParentActivity(), url != null ? url : webpage != null ? webpage.url : null);
                }

                // inline buttons: link buttons open the link, the rest need the channel itself
                public void didPressBotButton(ChatMessageCell cell, TL_keyboard.KeyboardButtonProto button) {
                    TL_keyboard.TL_inlineButtonTypeUrl urlButton = TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeUrl.class);
                    if (urlButton != null && urlButton.url != null) {
                        handleLink(urlButton.url);
                    } else {
                        openPost(current);
                    }
                }

                public void didPressReplyMessage(ChatMessageCell cell, int id, float x, float y, boolean longpress) {
                    if (longpress) showPostActions(current); else openPost(current);
                }

                public void didPressViaBot(ChatMessageCell cell, String username) {
                    if (username != null && getParentActivity() != null) {
                        MessagesController.getInstance(currentAccount).openByUserName(username, TGramFeedActivity.this, 0);
                    }
                }

                public void didPressChannelAvatar(ChatMessageCell cell, TLRPC.Chat chat, int postId, float touchX, float touchY, boolean asForward) {
                    openChannel(current);
                }

                public void didPressCommentButton(ChatMessageCell cell) {
                    openPost(current);
                }

                public void didPressReaction(ChatMessageCell cell, TLRPC.ReactionCount reaction, boolean longpress, float x, float y) {
                    if (longpress) showPostActions(current); else openPost(current);
                }

                public void didPressOther(ChatMessageCell cell, float otherX, float otherY) {
                    openPost(current);
                }

                public boolean needPlayMessage(ChatMessageCell cell, MessageObject messageObject, boolean muted) {
                    try {
                        return MediaController.getInstance().playMessage(messageObject);
                    } catch (Throwable e) {
                        org.telegram.messenger.FileLog.e(e);
                        return false;
                    }
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
            dateView.setText(LocaleController.getInstance().getFormatterDay().format(post.date * 1000L));
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
