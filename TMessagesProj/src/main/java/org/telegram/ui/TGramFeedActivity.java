package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.TrustedBadges;
import org.telegram.messenger.TrustedFeed;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;

/**
 * TrustedGram Feed: all posts of the channels you picked, in one chat-style list.
 * - bubble cards on the chat wallpaper, channel name + verified badge, text with links
 * - albums are merged into one card, video play badge, file rows
 * - reaction chips, views, edited mark, comment counter
 * - day separators, load more, pull the newest / oldest first option
 * - header: mark all as read (also marks the original channels), feed settings
 * Tap a post to open it inside its channel. Long press for more actions.
 */
public class TGramFeedActivity extends BaseFragment {

    private static final int MENU_SETTINGS = 1, MENU_READ = 2, MENU_REFRESH = 3;
    private static final int MAX_CHANNELS = 40;
    private static final int PAGE = 8;

    private static final int T_DATE = 0, T_POST = 1, T_MORE = 2;

    private static class Post {
        ArrayList<MessageObject> messages = new ArrayList<>();
        TLRPC.Chat chat;
        int date;
        long groupId;
    }

    private static class Row {
        int type;
        Post post;
        int date;
    }

    private final ArrayList<Post> posts = new ArrayList<>();
    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;
    private TextView statusView;
    private int loadGeneration;
    private int pending;
    private int perChannel = PAGE;
    private boolean loading;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle("Feed");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_READ, R.drawable.msg_markread);
        menu.addItem(MENU_SETTINGS, R.drawable.msg_customize);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == MENU_SETTINGS) presentFragment(new TGramFeedChannelsActivity());
                else if (id == MENU_READ) confirmMarkAllRead();
                else if (id == MENU_REFRESH) reload();
            }
        });

        FrameLayout frame = new FrameLayout(context) {
            private final Paint fallback = new Paint();
            @Override
            protected void dispatchDraw(Canvas canvas) {
                Drawable wallpaper = Theme.getCachedWallpaper();
                if (wallpaper != null) {
                    wallpaper.setBounds(0, 0, getWidth(), getHeight());
                    wallpaper.draw(canvas);
                } else {
                    fallback.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
                    canvas.drawRect(0, 0, getWidth(), getHeight(), fallback);
                }
                super.dispatchDraw(canvas);
            }
        };
        frame.setWillNotDraw(false);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(6), 0, dp(14));
        adapter = new Adapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            Row r = rows.get(position);
            if (r.type == T_MORE) {
                if (!loading) {
                    perChannel += PAGE;
                    load();
                }
            } else if (r.type == T_POST) {
                openPost(r.post);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return false;
            Row r = rows.get(position);
            if (r.type != T_POST) return false;
            showPostActions(r.post);
            return true;
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        statusView = new TextView(context);
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusView.setPadding(dp(32), 0, dp(32), 0);
        frame.addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = frame;
        updateSubtitle();
        load();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (TrustedFeed.dirty) reload();
        updateSubtitle();
    }

    private void updateSubtitle() {
        if (actionBar == null) return;
        int count = TrustedFeed.getIncludedChannels(currentAccount).size();
        String s = count + (count == 1 ? " channel" : " channels");
        if (TrustedFeed.isUnreadCounter()) {
            int unread = TrustedFeed.getUnreadCount(currentAccount);
            if (unread > 0) s += " \u2022 " + unread + " unread";
        }
        actionBar.setSubtitle(s);
    }

    private void confirmMarkAllRead() {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Mark All as Read");
        b.setMessage("Mark all posts in your feed channels as read? This will also mark them as read in the original channels.");
        b.setNegativeButton("Cancel", null);
        b.setPositiveButton("Mark as read", (d, w) -> {
            TrustedFeed.markAllRead(currentAccount);
            updateSubtitle();
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        showDialog(b.create());
    }

    private void openPost(Post p) {
        Bundle args = new Bundle();
        args.putLong("chat_id", p.chat.id);
        args.putInt("message_id", p.messages.get(0).getId());
        presentFragment(new ChatActivity(args));
    }

    private void showPostActions(Post p) {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Integer> actions = new ArrayList<>();
        labels.add("Open post"); actions.add(0);
        labels.add("Open channel"); actions.add(1);
        final CharSequence text = postText(p);
        if (!TextUtils.isEmpty(text)) { labels.add("Copy text"); actions.add(2); }
        final String username = ChatObject.getPublicUsername(p.chat);
        if (username != null) { labels.add("Copy link"); actions.add(3); }
        BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
        builder.setItems(labels.toArray(new CharSequence[0]), (DialogInterface dialog, int which) -> {
            int a = actions.get(which);
            if (a == 0) openPost(p);
            else if (a == 1) {
                Bundle args = new Bundle();
                args.putLong("chat_id", p.chat.id);
                presentFragment(new ChatActivity(args));
            } else if (a == 2) {
                AndroidUtilities.addToClipboard(text);
            } else if (a == 3) {
                AndroidUtilities.addToClipboard("https://t.me/" + username + "/" + p.messages.get(0).getId());
            }
        });
        showDialog(builder.create());
    }

    private static CharSequence postText(Post p) {
        for (MessageObject mo : p.messages) {
            CharSequence t = bodyOf(mo);
            if (!TextUtils.isEmpty(t)) return t;
        }
        return null;
    }

    private static CharSequence bodyOf(MessageObject mo) {
        if (mo.isPhoto() || mo.isVideo() || mo.isGif()) return mo.caption;
        if (mo.messageOwner.media instanceof TLRPC.TL_messageMediaDocument) return mo.caption;
        return mo.messageText;
    }

    private void reload() {
        perChannel = PAGE;
        load();
    }

    private void load() {
        TrustedFeed.dirty = false;
        final int account = currentAccount;
        ArrayList<TLRPC.Chat> channels = new ArrayList<>();
        for (TLRPC.Chat c : TrustedFeed.getIncludedChannels(account)) {
            if (channels.size() < MAX_CHANNELS) channels.add(c);
        }
        final int generation = ++loadGeneration;
        posts.clear();
        updateSubtitle();
        if (channels.isEmpty()) {
            rows.clear();
            adapter.notifyDataSetChanged();
            statusView.setText("Your feed is empty.\nTap the settings icon at the top to choose channels.");
            statusView.setVisibility(View.VISIBLE);
            return;
        }
        loading = true;
        if (rows.isEmpty()) {
            statusView.setText("Loading feed...");
            statusView.setVisibility(View.VISIBLE);
        }
        pending = channels.size();
        for (TLRPC.Chat chat : channels) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = MessagesController.getInstance(account).getInputPeer(-chat.id);
            req.limit = perChannel;
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
                    Post album = null;
                    // history arrives newest first; albums are consecutive messages with one grouped_id
                    for (TLRPC.Message m : res.messages) {
                        if (m instanceof TLRPC.TL_messageEmpty || m instanceof TLRPC.TL_messageService) continue;
                        MessageObject mo = new MessageObject(account, m, users, chats, true, false);
                        if (m.grouped_id != 0 && album != null && album.groupId == m.grouped_id) {
                            album.messages.add(0, mo);
                            continue;
                        }
                        Post p = new Post();
                        p.messages.add(mo);
                        p.chat = chat;
                        p.date = m.date;
                        p.groupId = m.grouped_id;
                        posts.add(p);
                        album = m.grouped_id != 0 ? p : null;
                    }
                }
                if (--pending == 0) {
                    loading = false;
                    render();
                }
            }));
        }
    }

    private void render() {
        final boolean newestFirst = TrustedFeed.isNewestFirst();
        Collections.sort(posts, (a, b) -> newestFirst ? b.date - a.date : a.date - b.date);
        rows.clear();
        if (posts.isEmpty()) {
            adapter.notifyDataSetChanged();
            statusView.setText("No posts found.");
            statusView.setVisibility(View.VISIBLE);
            return;
        }
        statusView.setVisibility(View.GONE);
        int lastDay = -1;
        for (Post p : posts) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(p.date * 1000L);
            int day = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
            if (day != lastDay) {
                Row d = new Row();
                d.type = T_DATE;
                d.date = p.date;
                rows.add(d);
                lastDay = day;
            }
            Row r = new Row();
            r.type = T_POST;
            r.post = p;
            rows.add(r);
        }
        Row more = new Row();
        more.type = T_MORE;
        rows.add(more);
        adapter.notifyDataSetChanged();
        updateSubtitle();
    }

    // ------------------------------------------------------------------ adapter

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != T_DATE;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v;
            if (viewType == T_DATE) {
                TextView t = new TextView(context);
                t.setTextSize(13);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(0xFFFFFFFF);
                t.setPadding(dp(12), dp(5), dp(12), dp(5));
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x66000000);
                g.setCornerRadius(dp(14));
                t.setBackground(g);
                FrameLayout wrap = new FrameLayout(context);
                wrap.addView(t, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 6, 0, 6));
                wrap.setTag(t);
                v = wrap;
            } else if (viewType == T_MORE) {
                TextView t = new TextView(context);
                t.setTextSize(15);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(0xFFFFFFFF);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x80000000);
                g.setCornerRadius(dp(22));
                t.setBackground(g);
                t.setText("Load older posts");
                FrameLayout wrap = new FrameLayout(context);
                wrap.addView(t, LayoutHelper.createFrame(180, 44, Gravity.CENTER, 0, 12, 0, 12));
                v = wrap;
            } else {
                v = new PostView(context);
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row r = rows.get(position);
            if (r.type == T_DATE) {
                ((TextView) holder.itemView.getTag()).setText(dayLabel(r.date));
            } else if (r.type == T_POST) {
                ((PostView) holder.itemView).bind(r.post);
            }
        }
    }

    private static String dayLabel(int date) {
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(date * 1000L);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "Today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "Yesterday";
        return LocaleController.formatDateChat(date);
    }

    // ------------------------------------------------------------------ one post bubble

    /** Small blue verified seal, drawn by hand so it needs no resources. */
    private static class SealDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        private final int size;

        SealDrawable(int size) {
            this.size = size;
        }

        @Override
        public void draw(Canvas canvas) {
            float s = size, cx = getBounds().left + s / 2f, cy = getBounds().top + s / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF3A9BFF);
            canvas.drawCircle(cx, cy, s / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(s * 0.12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            check.reset();
            check.moveTo(cx - s * 0.22f, cy + s * 0.01f);
            check.lineTo(cx - s * 0.05f, cy + s * 0.18f);
            check.lineTo(cx + s * 0.24f, cy - s * 0.16f);
            canvas.drawPath(check, p);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    private class PostView extends FrameLayout {
        private final LinearLayout bubble;

        PostView(Context context) {
            super(context);
            bubble = new LinearLayout(context);
            bubble.setOrientation(LinearLayout.VERTICAL);
            addView(bubble, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(Post post) {
            final Context context = getContext();
            bubble.removeAllViews();
            boolean wide = TrustedFeed.isWidePosts();
            LayoutParams lp = (LayoutParams) bubble.getLayoutParams();
            lp.leftMargin = dp(wide ? 6 : 8);
            lp.rightMargin = dp(wide ? 6 : 40);
            lp.topMargin = dp(3);
            lp.bottomMargin = dp(3);
            bubble.setLayoutParams(lp);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Theme.getColor(Theme.key_chat_inBubble));
            bg.setCornerRadius(dp(16));
            bubble.setBackground(bg);
            bubble.setPadding(0, 0, 0, dp(8));

            final MessageObject first = post.messages.get(0);

            // ---- header: avatar, name (+ badge), date
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(18));
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(post.chat);
            avatar.setForUserOrChat(post.chat, avatarDrawable);
            header.addView(avatar, LayoutHelper.createLinear(36, 36));
            TextView name = new TextView(context);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn));
            name.setText(post.chat.title);
            if (TrustedBadges.isVerified(post.chat)) {
                SealDrawable seal = new SealDrawable(dp(15));
                seal.setBounds(0, 0, dp(15), dp(15));
                name.setCompoundDrawables(null, null, seal, null);
                name.setCompoundDrawablePadding(dp(5));
            }
            header.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 8, 0));
            bubble.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10, 8, 10, 4));

            // ---- media
            final ArrayList<MessageObject> visual = new ArrayList<>();
            for (MessageObject mo : post.messages) {
                if ((mo.isPhoto() || mo.isVideo() || mo.isGif()) && mo.photoThumbs != null && !mo.photoThumbs.isEmpty()) visual.add(mo);
            }
            if (visual.size() == 1) {
                bubble.addView(makeImage(context, visual.get(0), 0, 0), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 220, 4, 4, 4, 2));
            } else if (visual.size() > 1) {
                int shown = Math.min(visual.size(), 4);
                for (int i = 0; i < shown; i += 2) {
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.addView(makeImage(context, visual.get(i), i, 0), LayoutHelper.createLinear(0, 150, 1f, 0, 0, 1, 0));
                    if (i + 1 < shown) {
                        row.addView(makeImage(context, visual.get(i + 1), i + 1, visual.size() > 4 && i + 1 == 3 ? visual.size() - 4 : 0), LayoutHelper.createLinear(0, 150, 1f, 1, 0, 0, 0));
                    }
                    bubble.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, i == 0 ? 4 : 1, 4, 0));
                }
            }

            // files
            for (MessageObject mo : post.messages) {
                if (mo.getDocument() != null && !mo.isPhoto() && !mo.isVideo() && !mo.isGif()) {
                    TextView f = new TextView(context);
                    f.setTextSize(14);
                    f.setSingleLine(true);
                    f.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    f.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn));
                    f.setText("\uD83D\uDCCE " + mo.getDocumentName());
                    f.setPadding(dp(12), dp(8), dp(12), dp(8));
                    GradientDrawable fg = new GradientDrawable();
                    fg.setColor(0x1F808080);
                    fg.setCornerRadius(dp(10));
                    f.setBackground(fg);
                    bubble.addView(f, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10, 6, 10, 0));
                }
            }

            // ---- text
            CharSequence text = postText(post);
            if (!TrustedFeed.isCompact() || visual.isEmpty()) {
                if (!TextUtils.isEmpty(text)) {
                    TextView body = new TextView(context);
                    body.setTextSize(16);
                    body.setMaxLines(14);
                    body.setEllipsize(TextUtils.TruncateAt.END);
                    body.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn));
                    body.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn));
                    body.setText(text);
                    body.setMovementMethod(LinkMovementMethod.getInstance());
                    body.setOnClickListener(v -> openPost(post));
                    body.setOnLongClickListener(v -> { showPostActions(post); return true; });
                    bubble.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 6, 12, 0));
                }
            }

            // ---- reactions
            TLRPC.TL_messageReactions reactions = null;
            if (first.messageOwner.reactions instanceof TLRPC.TL_messageReactions) reactions = (TLRPC.TL_messageReactions) first.messageOwner.reactions;
            if (reactions != null && reactions.results != null && !reactions.results.isEmpty()) {
                LinearLayout chips = new LinearLayout(context);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                int count = 0;
                for (TLRPC.TL_reactionCount rc : reactions.results) {
                    if (count++ >= 5) break;
                    String emoji = rc.reaction instanceof TLRPC.TL_reactionEmoji ? ((TLRPC.TL_reactionEmoji) rc.reaction).emoticon : "\u2605";
                    TextView chip = new TextView(context);
                    chip.setTextSize(13);
                    chip.setTypeface(Typeface.DEFAULT_BOLD);
                    chip.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn));
                    chip.setText(emoji + " " + LocaleController.formatShortNumber(rc.count, null));
                    chip.setPadding(dp(10), dp(4), dp(10), dp(4));
                    GradientDrawable cg = new GradientDrawable();
                    cg.setColor(0x22808080);
                    cg.setCornerRadius(dp(14));
                    chip.setBackground(cg);
                    chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
                }
                bubble.addView(chips, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 12, 8, 12, 0));
            }

            // ---- footer: comments, views, edited, time
            LinearLayout footer = new LinearLayout(context);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            if (first.messageOwner.replies != null && first.messageOwner.replies.replies > 0) {
                TextView cm = new TextView(context);
                cm.setTextSize(13);
                cm.setTypeface(Typeface.DEFAULT_BOLD);
                cm.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn));
                cm.setText(first.messageOwner.replies.replies + (first.messageOwner.replies.replies == 1 ? " comment" : " comments"));
                footer.addView(cm, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            } else {
                footer.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
            }
            StringBuilder meta = new StringBuilder();
            if (first.messageOwner.views > 0) meta.append(LocaleController.formatShortNumber(first.messageOwner.views, null)).append(" views   ");
            if (first.messageOwner.edit_date != 0) meta.append("edited   ");
            meta.append(android.text.format.DateFormat.getTimeFormat(context).format(new Date(first.messageOwner.date * 1000L)));
            TextView metaView = new TextView(context);
            metaView.setTextSize(12);
            metaView.setTextColor(Theme.getColor(Theme.key_chat_inTimeText));
            metaView.setText(meta.toString());
            footer.addView(metaView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            bubble.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 6, 12, 0));
        }

        private View makeImage(Context context, MessageObject mo, int index, int moreCount) {
            FrameLayout cell = new FrameLayout(context);
            BackupImageView photo = new BackupImageView(context);
            photo.setRoundRadius(dp(10));
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(mo.photoThumbs, AndroidUtilities.getPhotoSize());
            if (size != null) {
                photo.setImage(ImageLocation.getForObject(size, mo.photoThumbsObject), "600_400", (Drawable) null, mo);
            }
            cell.addView(photo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            if (mo.isVideo() || mo.isGif()) {
                TextView play = new TextView(context);
                play.setText("\u25B6");
                play.setTextColor(0xFFFFFFFF);
                play.setTextSize(18);
                play.setGravity(Gravity.CENTER);
                GradientDrawable pg = new GradientDrawable();
                pg.setShape(GradientDrawable.OVAL);
                pg.setColor(0x99000000);
                play.setBackground(pg);
                cell.addView(play, LayoutHelper.createFrame(46, 46, Gravity.CENTER));
                if (mo.isVideo()) {
                    TextView dur = new TextView(context);
                    dur.setTextSize(11);
                    dur.setTextColor(0xFFFFFFFF);
                    dur.setText(AndroidUtilities.formatShortDuration(mo.getDuration()));
                    dur.setPadding(dp(6), dp(2), dp(6), dp(2));
                    GradientDrawable dg = new GradientDrawable();
                    dg.setColor(0x99000000);
                    dg.setCornerRadius(dp(8));
                    dur.setBackground(dg);
                    cell.addView(dur, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 8, 8, 0, 0));
                }
            }
            if (moreCount > 0) {
                TextView more = new TextView(context);
                more.setText("+" + moreCount);
                more.setTextColor(0xFFFFFFFF);
                more.setTextSize(24);
                more.setTypeface(Typeface.DEFAULT_BOLD);
                more.setGravity(Gravity.CENTER);
                GradientDrawable mg = new GradientDrawable();
                mg.setColor(0x80000000);
                mg.setCornerRadius(dp(10));
                more.setBackground(mg);
                cell.addView(more, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
            return cell;
        }
    }
}
