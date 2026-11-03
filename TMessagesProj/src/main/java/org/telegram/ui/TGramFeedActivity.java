package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.app.Dialog;
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

import androidx.recyclerview.widget.GridLayoutManagerFixed;
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
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.AlertsCreator;
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
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.ReactionsContainerLayout;
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
public class TGramFeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

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
    private ChatActivity callbackProxy;
    private final HashMap<Long, Integer> oldestId = new HashMap<>();
    private final java.util.HashSet<Long> endReached = new java.util.HashSet<>();
    private final HashMap<String, Post> knownPosts = new HashMap<>();
    private boolean loadingOlder;
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
    private TextView floatingDate;
    private FrameLayout rootFrame;
    private View[] normalBar;
    private View[] selectionBar;
    private TextView selCount;
    private ImageView selLink;
    private final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
    private FrameLayout menuOverlay;
    private FrameLayout pinnedBar;
    private TextView pinnedTitle;
    private TextView pinnedText;
    private final ArrayList<Post> pinnedPosts = new ArrayList<>();
    private int pinnedCursor;
    private final Runnable hideFloatingDate = () -> {
        if (floatingDate != null) floatingDate.animate().alpha(0f).setDuration(220).start();
    };
    private int loadGeneration;
    private int pending;

    // ------------------------------------------------------------------ screen

    /** Solid dark / light pill (no see-through), like the exteraGram feed bar. */
    private int barColor() {
        return androidx.core.graphics.ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_actionBarDefault), 0xF2);
    }

    private int barText() {
        return Theme.getColor(Theme.key_actionBarDefaultTitle);
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
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateReactions);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateReactions);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didUpdateReactions || args.length < 3
                || !(args[0] instanceof Long) || !(args[1] instanceof Integer)
                || !(args[2] instanceof TLRPC.TL_messageReactions)) {
            return;
        }
        long did = (Long) args[0];
        int msgId = (Integer) args[1];
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.type != TYPE_POST || -it.post.chat.id != did) continue;
            boolean changed = false;
            for (MessageObject m : it.post.messages) {
                if (m.getId() == msgId) {
                    MessageObject.updateReactions(m.messageOwner, (TLRPC.TL_messageReactions) args[2]);
                    m.forceUpdate = true;
                    m.reactionsChanged = true;
                    changed = true;
                }
            }
            if (changed && adapter != null) adapter.notifyItemChanged(i);
        }
    }

    /**
     * Invisible ChatActivity that is never shown. SendMessagesHelper.sendCallback() needs one to show its
     * answers, so every dialog / alert / screen it wants is routed to this feed instead.
     */
    private ChatActivity getCallbackProxy() {
        if (callbackProxy == null) {
            final TGramFeedActivity feed = this;
            callbackProxy = new ChatActivity(new Bundle()) {
                @Override
                public Activity getParentActivity() {
                    return feed.getParentActivity();
                }

                @Override
                public Dialog showDialog(Dialog dialog) {
                    return feed.showDialog(dialog);
                }

                @Override
                public boolean presentFragment(BaseFragment fragment) {
                    return feed.presentFragment(fragment);
                }

                @Override
                public void showAlert(String name, String message) {
                    if (feed.getParentActivity() == null || message == null) return;
                    AlertDialog.Builder b = new AlertDialog.Builder(feed.getParentActivity());
                    if (name != null) b.setTitle(name);
                    b.setMessage(message);
                    b.setPositiveButton("OK", null);
                    feed.showDialog(b.create());
                }

                @Override
                public void showOpenGameAlert(TLRPC.TL_game game, MessageObject messageObject, String urlStr, boolean ask, long uid) {
                    AlertsCreator.showOpenUrlAlert(feed, urlStr, false, false);
                }
            };
        }
        return callbackProxy;
    }

    /** Tap on a reaction chip: add / remove our reaction in place, like in the channel. */
    private void toggleReaction(MessageObject mo, TLRPC.ReactionCount reaction, Post post) {
        if (mo == null || reaction == null || reaction.reaction == null) return;
        if (reaction.reaction instanceof TLRPC.TL_reactionPaid) {
            openPost(post);
            return;
        }
        ReactionsLayoutInBubble.VisibleReaction clicked = ReactionsLayoutInBubble.VisibleReaction.fromTL(reaction.reaction);
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> list = new ArrayList<>();
        if (mo.messageOwner.reactions != null) {
            for (TLRPC.ReactionCount rc : mo.messageOwner.reactions.results) {
                if (rc.chosen && rc.reaction != null && !clicked.isSame(rc.reaction) && !(rc.reaction instanceof TLRPC.TL_reactionPaid)) {
                    list.add(ReactionsLayoutInBubble.VisibleReaction.fromTL(rc.reaction));
                }
            }
        }
        if (!reaction.chosen) list.add(clicked);
        SendMessagesHelper.getInstance(currentAccount).sendReaction(mo, list, reaction.chosen ? null : clicked, false, false, this, null);
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
        // Message cells consume their own touch events, so a long press on media/text
        // does not reliably reach PostView's listener. Handle it at the list level too.
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) return false;
            Item item = items.get(position);
            if (item.type != TYPE_POST) return false;
            showPostActions(item.post);
            return true;
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateDownButton(true);
                if (dy != 0) updateFloatingDate();
                if (dy < 0 && layoutManager.findFirstVisibleItemPosition() <= 4) {
                    loadOlder();
                }
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (floatingDate != null) {
                        floatingDate.removeCallbacks(hideFloatingDate);
                        floatingDate.postDelayed(hideFloatingDate, 1200);
                    }
                    markSeenPosts();
                }
            }
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // the day of the post you are looking at, shown under the top bar while you scroll
        floatingDate = new TextView(context);
        floatingDate.setTextSize(13);
        floatingDate.setTypeface(Typeface.DEFAULT_BOLD);
        floatingDate.setTextColor(barText());
        floatingDate.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable fdg = new GradientDrawable();
        fdg.setColor(barColor());
        fdg.setCornerRadius(dp(16));
        floatingDate.setBackground(fdg);
        floatingDate.setAlpha(0f);
        FrameLayout.LayoutParams fdl = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        fdl.topMargin = AndroidUtilities.statusBarHeight + dp(60);
        frame.addView(floatingDate, fdl);

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
        rootFrame = frame;
        normalBar = new View[]{backWrap, titlePill, actionPill};

        // ---- selection bar (tap a post): [x]  N Selected  [copy] [link] [forward]
        ImageView selClose = barIcon(context, R.drawable.ic_close_white);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setShape(GradientDrawable.OVAL);
        sbg.setColor(barColor());
        FrameLayout selBack = new FrameLayout(context);
        selBack.setBackground(sbg);
        selBack.addView(selClose, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        selBack.setOnClickListener(v -> clearSelection());
        FrameLayout.LayoutParams sblp = LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.LEFT, 8, 0, 0, 0);
        sblp.topMargin = barTop;
        frame.addView(selBack, sblp);

        selCount = new TextView(context);
        selCount.setTextSize(17);
        selCount.setTypeface(Typeface.DEFAULT_BOLD);
        selCount.setTextColor(barText());
        selCount.setPadding(dp(8), 0, dp(8), 0);
        View selPill = pill(context, selCount);
        FrameLayout.LayoutParams splp = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.TOP | Gravity.LEFT, 60, 0, 0, 0);
        splp.topMargin = barTop;
        frame.addView(selPill, splp);

        LinearLayout selTools = new LinearLayout(context);
        selTools.setOrientation(LinearLayout.HORIZONTAL);
        ImageView selCopy = barIcon(context, R.drawable.msg_copy);
        selCopy.setOnClickListener(v -> copySelectedText());
        selTools.addView(selCopy, LayoutHelper.createLinear(40, 44));
        selLink = barIcon(context, R.drawable.msg_link);
        selLink.setOnClickListener(v -> {
            ArrayList<Post> sel = selectedPosts();
            if (sel.size() == 1) {
                AndroidUtilities.addToClipboard(linkOf(sel.get(0)));
                toast("Link copied");
            }
        });
        selTools.addView(selLink, LayoutHelper.createLinear(40, 44));
        ImageView selForward = barIcon(context, R.drawable.msg_forward);
        selForward.setOnClickListener(v -> forwardPosts(selectedPosts()));
        selTools.addView(selForward, LayoutHelper.createLinear(40, 44));
        View selActions = pill(context, selTools);
        FrameLayout.LayoutParams salp = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.TOP | Gravity.RIGHT, 0, 0, 8, 0);
        salp.topMargin = barTop;
        frame.addView(selActions, salp);
        selectionBar = new View[]{selBack, selPill, selActions};
        for (View v : selectionBar) v.setVisibility(View.GONE);

        // bottom "Forward" button while posts are selected
        final TextView forwardBtn = new TextView(context);
        forwardBtn.setText("Forward");
        forwardBtn.setTextSize(16);
        forwardBtn.setTypeface(Typeface.DEFAULT_BOLD);
        forwardBtn.setTextColor(barText());
        forwardBtn.setGravity(Gravity.CENTER);
        forwardBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.msg_forward, 0, 0, 0);
        forwardBtn.setCompoundDrawablePadding(dp(8));
        forwardBtn.getCompoundDrawables()[0].setColorFilter(barText(), android.graphics.PorterDuff.Mode.MULTIPLY);
        forwardBtn.setPadding(dp(26), 0, dp(26), 0);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(barColor());
        fbg.setCornerRadius(dp(24));
        forwardBtn.setBackground(fbg);
        forwardBtn.setOnClickListener(v -> forwardPosts(selectedPosts()));
        forwardBtn.setVisibility(View.GONE);
        frame.addView(forwardBtn, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));
        selectionBar = new View[]{selBack, selPill, selActions, forwardBtn};

        // ---- pinned posts bar, like the pinned message bar of a chat
        pinnedBar = new FrameLayout(context);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(barColor());
        pbg.setCornerRadius(dp(14));
        pinnedBar.setBackground(pbg);
        View accent = new View(context);
        GradientDrawable ag = new GradientDrawable();
        ag.setColor(Theme.getColor(Theme.key_chat_messageLinkIn));
        ag.setCornerRadius(dp(2));
        accent.setBackground(ag);
        pinnedBar.addView(accent, LayoutHelper.createFrame(3, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        LinearLayout pinTexts = new LinearLayout(context);
        pinTexts.setOrientation(LinearLayout.VERTICAL);
        pinnedTitle = new TextView(context);
        pinnedTitle.setTextSize(13);
        pinnedTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pinnedTitle.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn));
        pinnedTitle.setSingleLine(true);
        pinTexts.addView(pinnedTitle);
        pinnedText = new TextView(context);
        pinnedText.setTextSize(14);
        pinnedText.setTextColor(barText());
        pinnedText.setSingleLine(true);
        pinnedText.setEllipsize(TextUtils.TruncateAt.END);
        pinTexts.addView(pinnedText);
        pinnedBar.addView(pinTexts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 22, 0, 52, 0));
        ImageView unpin = barIcon(context, R.drawable.msg_unpin);
        unpin.setOnClickListener(v -> {
            if (!pinnedPosts.isEmpty()) setPinned(pinnedPosts.get(Math.min(pinnedCursor, pinnedPosts.size() - 1)), false);
        });
        pinnedBar.addView(unpin, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        pinnedBar.setOnClickListener(v -> scrollToPinned());
        pinnedBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams pblp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 8, 0, 8, 0);
        pblp.topMargin = AndroidUtilities.statusBarHeight + dp(60);
        frame.addView(pinnedBar, pblp);

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
        oldestId.clear();
        endReached.clear();
        knownPosts.clear();
        loadingOlder = false;
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

        // Group only within this response first, then merge by grouped_id with any
        // fragment already loaded from the adjacent history page.
        HashMap<Long, Post> albums = new HashMap<>();
        ArrayList<Post> channelPosts = new ArrayList<>();
        int minId = Integer.MAX_VALUE;
        for (TLRPC.Message m : res.messages) {
            minId = Math.min(minId, m.id);
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

        // Use the oldest raw message id, not the oldest rendered post id. This keeps
        // pagination moving past service messages and incomplete album fragments.
        if (res.messages.size() < PER_CHANNEL) {
            endReached.add(chat.id);
        }
        if (minId != Integer.MAX_VALUE) {
            Integer prev = oldestId.get(chat.id);
            oldestId.put(chat.id, prev == null ? minId : Math.min(prev, minId));
        }

        for (Post pagePost : channelPosts) {
            if (pagePost.messages.isEmpty()) continue;
            final long groupedId = pagePost.first().messageOwner.grouped_id;
            final String key = groupedId != 0
                    ? chat.id + ":g:" + groupedId
                    : chat.id + ":m:" + pagePost.first().getId();

            Post target = knownPosts.get(key);
            if (target == null) {
                target = pagePost;
                knownPosts.put(key, target);
                posts.add(target);
            } else {
                // The same album can straddle two getHistory pages. Merge its
                // remaining items instead of dropping the boundary fragment.
                java.util.HashSet<Integer> existingIds = new java.util.HashSet<>();
                for (MessageObject existing : target.messages) {
                    existingIds.add(existing.getId());
                }
                for (MessageObject incoming : pagePost.messages) {
                    if (existingIds.add(incoming.getId())) {
                        target.messages.add(incoming);
                    }
                }
            }

            if (groupedId != 0) {
                Collections.sort(target.messages, (a, b) -> a.getId() - b.getId());
                MessageObject.GroupedMessages g = new MessageObject.GroupedMessages();
                g.groupId = groupedId;
                g.messages.addAll(target.messages);
                g.calculate();
                target.group = g;
                // The album's displayed timestamp belongs to its newest item.
                target.date = target.messages.get(target.messages.size() - 1).messageOwner.date;
            } else {
                target.group = null;
                target.date = target.first().messageOwner.date;
            }
        }
    }

    private void render() {
        // oldest first, newest at the bottom, like a real channel
        Collections.sort(posts, (a, b) -> a.date != b.date ? a.date - b.date : a.first().getId() - b.first().getId());

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
        refreshPins();
        scrollToStart();
    }

    private boolean hasOlder() {
        for (TLRPC.Chat c : loadedChannels) {
            if (!endReached.contains(c.id)) return true;
        }
        return false;
    }

    /** Scrolled to the top: fetch the next older page of every channel and keep the reading position. */
    private void loadOlder() {
        if (loadingOlder || initialLoadPending() || loadedChannels.isEmpty() || !hasOlder()) return;
        loadingOlder = true;
        final int account = currentAccount;
        final int generation = loadGeneration;
        final ArrayList<TLRPC.Chat> toLoad = new ArrayList<>();
        for (TLRPC.Chat c : loadedChannels) {
            if (!endReached.contains(c.id) && oldestId.containsKey(c.id)) toLoad.add(c);
        }
        if (toLoad.isEmpty()) {
            loadingOlder = false;
            return;
        }
        final int[] left = {toLoad.size()};
        for (final TLRPC.Chat chat : toLoad) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = MessagesController.getInstance(account).getInputPeer(-chat.id);
            req.limit = PER_CHANNEL;
            req.offset_id = oldestId.get(chat.id);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (generation != loadGeneration) return;
                if (response instanceof TLRPC.messages_Messages) {
                    addHistory(account, chat, (TLRPC.messages_Messages) response);
                } else {
                    endReached.add(chat.id);
                }
                if (--left[0] == 0) {
                    renderKeepingPosition();
                    loadingOlder = false;
                }
            }));
        }
    }

    private boolean initialLoadPending() {
        return pending > 0;
    }

    private void renderKeepingPosition() {
        Post anchor = null;
        int anchorTop = 0;
        int first = layoutManager.findFirstVisibleItemPosition();
        for (int i = Math.max(0, first); i < items.size() && anchor == null; i++) {
            if (items.get(i).type == TYPE_POST) {
                View v = layoutManager.findViewByPosition(i);
                if (v != null) {
                    anchor = items.get(i).post;
                    anchorTop = v.getTop() - listView.getPaddingTop();
                }
            }
        }
        render();
        if (anchor != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).type == TYPE_POST && items.get(i).post == anchor) {
                    layoutManager.scrollToPositionWithOffset(i, anchorTop);
                    break;
                }
            }
        }
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

    private void updateFloatingDate() {
        if (floatingDate == null || items.isEmpty() || layoutManager == null) return;
        int pos = layoutManager.findFirstVisibleItemPosition();
        if (pos < 0 || pos >= items.size()) return;
        int date = 0;
        for (int i = pos; i >= 0 && date == 0; i--) {
            Item it = items.get(i);
            if (it.post != null) date = it.post.first().messageOwner.date;
            else if (it.dateObject != null) date = it.dateObject.messageOwner.date;
        }
        if (date == 0) return;
        floatingDate.setText(dateLabel(date));
        floatingDate.removeCallbacks(hideFloatingDate);
        floatingDate.animate().cancel();
        floatingDate.setAlpha(1f);
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

    // ------------------------------------------------------------------ selection

    private static String keyOf(Post p) {
        return p.chat.id + "_" + p.first().getId();
    }

    private ArrayList<Post> selectedPosts() {
        ArrayList<Post> out = new ArrayList<>();
        for (Post p : posts) {
            if (selected.contains(keyOf(p))) out.add(p);
        }
        return out;
    }

    private void toggleSelect(Post p) {
        if (p == null) return;
        String k = keyOf(p);
        if (!selected.remove(k)) selected.add(k);
        updateSelectionUi();
    }

    private void clearSelection() {
        selected.clear();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        final boolean sel = !selected.isEmpty();
        if (normalBar != null) for (View v : normalBar) v.setVisibility(sel ? View.GONE : View.VISIBLE);
        if (selectionBar != null) for (View v : selectionBar) v.setVisibility(sel ? View.VISIBLE : View.GONE);
        if (selCount != null) selCount.setText(selected.size() + " Selected");
        if (selLink != null) selLink.setVisibility(selected.size() == 1 ? View.VISIBLE : View.GONE);
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) listView.getChildAt(i).invalidate();
        }
    }

    private void copySelectedText() {
        StringBuilder sb = new StringBuilder();
        for (Post p : selectedPosts()) {
            CharSequence t = textOf(p);
            if (TextUtils.isEmpty(t)) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        if (sb.length() == 0) {
            toast("Nothing to copy");
            return;
        }
        AndroidUtilities.addToClipboard(sb.toString());
        toast("Text copied");
        clearSelection();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (menuOverlay != null) {
            if (invoked) hideMenuOverlay();
            return false;
        }
        if (!selected.isEmpty()) {
            if (invoked) clearSelection();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    // ------------------------------------------------------------------ long-press menu with reactions

    private View findPostView(Post p) {
        if (listView == null) return null;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof PostView && ((PostView) child).current == p) return child;
        }
        return null;
    }

    private void hideMenuOverlay() {
        if (menuOverlay != null && rootFrame != null) {
            rootFrame.removeView(menuOverlay);
        }
        menuOverlay = null;
    }

    private void reactFromMenu(MessageObject mo, ReactionsLayoutInBubble.VisibleReaction vr, Post post) {
        if (mo == null || vr == null) return;
        if (vr.isStar) {
            openPost(post);
            return;
        }
        boolean chosen = false;
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> list = new ArrayList<>();
        if (mo.messageOwner.reactions != null) {
            for (TLRPC.ReactionCount rc : mo.messageOwner.reactions.results) {
                if (rc.chosen && rc.reaction != null && !(rc.reaction instanceof TLRPC.TL_reactionPaid)) {
                    if (vr.isSame(rc.reaction)) chosen = true;
                    else list.add(ReactionsLayoutInBubble.VisibleReaction.fromTL(rc.reaction));
                }
            }
        }
        if (!chosen) list.add(vr);
        SendMessagesHelper.getInstance(currentAccount).sendReaction(mo, list, chosen ? null : vr, false, false, this, null);
    }

    private void addMenuRow(LinearLayout card, int icon, String text, boolean top, boolean bottom, final Runnable action) {
        ActionBarMenuSubItem item = new ActionBarMenuSubItem(getParentActivity(), top, bottom, getResourceProvider());
        item.setTextAndIcon(text, icon);
        item.setOnClickListener(v -> {
            hideMenuOverlay();
            action.run();
        });
        card.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
    }

    /** Hold a post: Telegram's reaction bar on top and the same kind of menu as in a channel. */
    private void showPostActions(final Post p) {
        if (p == null || getParentActivity() == null || rootFrame == null) return;
        hideMenuOverlay();
        final Context ctx = getParentActivity();
        final View anchor = findPostView(p);

        final FrameLayout overlay = new FrameLayout(ctx);
        overlay.setBackgroundColor(0x73000000);
        overlay.setOnClickListener(v -> hideMenuOverlay());

        final LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);

        // reactions
        MessageObject primary = p.group != null ? p.group.findPrimaryMessageObject() : p.first();
        if (primary == null) primary = p.first();
        final MessageObject reactTarget = primary;
        try {
            final ReactionsContainerLayout reactions = new ReactionsContainerLayout(ReactionsContainerLayout.TYPE_DEFAULT, this, ctx, currentAccount, getResourceProvider());
            reactions.forceAttachToParent = true;
            final int pad = 22, sPad = 24;
            reactions.setPadding(dp(4) + (LocaleController.isRTL ? 0 : sPad), dp(4), dp(4) + (LocaleController.isRTL ? sPad : 0), dp(pad));
            reactions.setDelegate(new ReactionsContainerLayout.ReactionsContainerDelegate() {
                @Override
                public void onReactionClicked(View v, ReactionsLayoutInBubble.VisibleReaction visibleReaction, boolean longpress, boolean addToRecent) {
                    hideMenuOverlay();
                    reactFromMenu(reactTarget, visibleReaction, p);
                }

                @Override
                public void hideMenu() {
                    hideMenuOverlay();
                }
            });
            TLRPC.ChatFull full = MessagesController.getInstance(currentAccount).getChatFull(p.chat.id);
            if (full == null) MessagesController.getInstance(currentAccount).loadFullChat(p.chat.id, 0, true);
            reactions.setMessage(reactTarget, full, true);
            reactions.setTransitionProgress(1);
            column.addView(reactions, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, (int) (52 + reactions.getTopOffset() / AndroidUtilities.density + pad), Gravity.LEFT, 0, 0, 0, -20));
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }

        // menu card
        final CharSequence text = textOf(p);
        final boolean pinned = TrustedFeed.isPinned(p.chat.id, p.first().getId());
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground)));
        if (android.os.Build.VERSION.SDK_INT >= 21) card.setElevation(dp(8));
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        names.add("Show in chat"); icons.add(R.drawable.msg_openin); acts.add(() -> openPost(p));
        if (!TextUtils.isEmpty(text)) {
            names.add("Copy"); icons.add(R.drawable.msg_copy);
            acts.add(() -> {
                AndroidUtilities.addToClipboard(text);
                toast("Text copied");
            });
        }
        names.add("Copy link"); icons.add(R.drawable.msg_link);
        acts.add(() -> {
            AndroidUtilities.addToClipboard(linkOf(p));
            toast("Link copied");
        });
        names.add("Forward"); icons.add(R.drawable.msg_forward); acts.add(() -> forward(p));
        names.add(pinned ? "Unpin post" : "Pin post"); icons.add(pinned ? R.drawable.msg_unpin : R.drawable.msg_pin);
        acts.add(() -> setPinned(p, !pinned));
        names.add("Select"); icons.add(R.drawable.msg_select); acts.add(() -> toggleSelect(p));
        for (int i = 0; i < names.size(); i++) {
            addMenuRow(card, icons.get(i), names.get(i), i == 0, i == names.size() - 1, acts.get(i));
        }
        column.addView(card, LayoutHelper.createLinear(220, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 0, 0));

        overlay.addView(column, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 16, 0, 16, 0));
        rootFrame.addView(overlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        menuOverlay = overlay;

        column.setAlpha(0f);
        column.post(() -> {
            if (menuOverlay != overlay) return;
            int[] a = new int[2], r = new int[2];
            if (anchor != null) anchor.getLocationInWindow(a);
            rootFrame.getLocationInWindow(r);
            float center = anchor != null ? a[1] - r[1] + anchor.getHeight() / 2f : rootFrame.getHeight() / 2f;
            float y = center - column.getHeight() / 2f;
            float min = AndroidUtilities.statusBarHeight + dp(64);
            float max = rootFrame.getHeight() - column.getHeight() - dp(16);
            column.setTranslationY(Math.max(min, Math.min(y, max)));
            column.animate().alpha(1f).setDuration(140).start();
        });
    }

    // ------------------------------------------------------------------ pinned posts

    private void setPinned(Post p, boolean pin) {
        TrustedFeed.setPinned(p.chat.id, p.first().getId(), pin);
        toast(pin ? "Post pinned" : "Post unpinned");
        refreshPins();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void refreshPins() {
        if (pinnedBar == null) return;
        pinnedPosts.clear();
        for (Post p : posts) {
            if (TrustedFeed.isPinned(p.chat.id, p.first().getId())) pinnedPosts.add(p);
        }
        // newest pinned post first, like the pinned bar of a chat
        Collections.sort(pinnedPosts, (a, b) -> b.date - a.date);
        if (pinnedCursor >= pinnedPosts.size()) pinnedCursor = 0;
        final boolean show = !pinnedPosts.isEmpty();
        pinnedBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            Post p = pinnedPosts.get(pinnedCursor);
            pinnedTitle.setText(pinnedPosts.size() > 1 ? "Pinned post #" + (pinnedCursor + 1) + " of " + pinnedPosts.size() : "Pinned post");
            CharSequence t = textOf(p);
            pinnedText.setText(TextUtils.isEmpty(t) ? p.chat.title + " \u2022 media" : p.chat.title + ": " + t.toString().replace('\n', ' '));
        }
        final int base = AndroidUtilities.statusBarHeight + dp(62);
        listView.setPadding(0, base + (show ? dp(54) : 0), 0, dp(12));
        if (floatingDate != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) floatingDate.getLayoutParams();
            lp.topMargin = AndroidUtilities.statusBarHeight + dp(60) + (show ? dp(54) : 0);
            floatingDate.setLayoutParams(lp);
        }
    }

    private void scrollToPinned() {
        if (pinnedPosts.isEmpty()) return;
        Post target = pinnedPosts.get(Math.min(pinnedCursor, pinnedPosts.size() - 1));
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).post == target) {
                layoutManager.scrollToPositionWithOffset(i, AndroidUtilities.statusBarHeight + dp(62) + dp(54));
                break;
            }
        }
        pinnedCursor = (pinnedCursor + 1) % pinnedPosts.size();
        refreshPins();
    }

    private void toast(String s) {
        if (getParentActivity() != null) Toast.makeText(getParentActivity(), s, Toast.LENGTH_SHORT).show();
    }

    private void forward(final Post p) {
        ArrayList<Post> one = new ArrayList<>();
        one.add(p);
        forwardPosts(one);
    }

    private void forwardPosts(final ArrayList<Post> list) {
        if (list == null || list.isEmpty()) return;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("canSelectTopics", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            for (MessagesStorage.TopicKey key : dids) {
                // one send per channel, so every forwarded batch comes from a single source chat
                java.util.LinkedHashMap<Long, ArrayList<MessageObject>> byChat = new java.util.LinkedHashMap<>();
                for (Post p : list) {
                    ArrayList<MessageObject> l = byChat.get(p.chat.id);
                    if (l == null) byChat.put(p.chat.id, l = new ArrayList<>());
                    l.addAll(p.messages);
                }
                for (ArrayList<MessageObject> l : byChat.values()) sendForward(currentAccount, l, key.dialogId);
            }
            fragment.finishFragment();
            clearSelection();
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
        private final RecyclerView albumList;
        private final ArrayList<MessageObject> albumMessages = new ArrayList<>();
        private MessageObject.GroupedMessages albumGroup;
        private RecyclerView.Adapter<RecyclerView.ViewHolder> albumAdapter;
        private final ArrayList<ChatMessageCell> cells = new ArrayList<>();
        private final ChatMessageCell.ChatMessageCellDelegate delegate;
        private Post current;
        private final android.graphics.Paint selPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            final boolean sel = current != null && selected.contains(keyOf(current));
            if (sel) {
                selPaint.setStyle(android.graphics.Paint.Style.FILL);
                selPaint.setColor(Theme.getColor(Theme.key_chat_selectedBackground));
                canvas.drawRect(0, 0, getWidth(), getHeight(), selPaint);
            }
            super.dispatchDraw(canvas);
            if (sel) {
                final float cx = dp(20), cy = getHeight() / 2f;
                selPaint.setStyle(android.graphics.Paint.Style.FILL);
                selPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                canvas.drawCircle(cx, cy, dp(11), selPaint);
                selPaint.setStyle(android.graphics.Paint.Style.STROKE);
                selPaint.setStrokeWidth(dp(2));
                selPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                selPaint.setColor(0xFFFFFFFF);
                canvas.drawLine(cx - dp(5), cy, cx - dp(1.5f), cy + dp(3.5f), selPaint);
                canvas.drawLine(cx - dp(1.5f), cy + dp(3.5f), cx + dp(5), cy - dp(3.5f), selPaint);
            }
        }

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

            // Albums are laid out exactly like in a Telegram chat: a 1000-column grid, every photo takes the share of
            // the row given by its position, so photos sit side by side in one collage instead of one under another.
            albumList = new RecyclerView(context);
            albumList.setOverScrollMode(View.OVER_SCROLL_NEVER);
            albumList.setItemAnimator(null);
            albumList.setNestedScrollingEnabled(false);
            albumList.setClipChildren(false);
            GridLayoutManagerFixed albumManager = new GridLayoutManagerFixed(context, 1000, LinearLayoutManager.VERTICAL, true) {
                @Override
                public boolean canScrollVertically() {
                    return false;
                }
            };
            albumManager.setSpanSizeLookup(new GridLayoutManagerFixed.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    if (albumGroup != null && position >= 0 && position < albumMessages.size()) {
                        MessageObject.GroupedMessagePosition pos = albumGroup.getPosition(albumMessages.get(position));
                        if (pos != null) return pos.spanSize;
                    }
                    return 1000;
                }
            });
            albumList.setLayoutManager(albumManager);
            albumList.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(android.graphics.Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                    outRect.bottom = 0;
                    if (!(view instanceof ChatMessageCell)) return;
                    ChatMessageCell cell = (ChatMessageCell) view;
                    MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                    if (group == null) return;
                    MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                    if (position == null || position.siblingHeights == null) return;
                    float maxHeight = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 0.5f;
                    int h = cell.getExtraInsetHeight();
                    for (int a = 0; a < position.siblingHeights.length; a++) {
                        h += (int) Math.ceil(maxHeight * position.siblingHeights[a]);
                    }
                    h += (position.maxY - position.minY) * Math.round(7 * AndroidUtilities.density);
                    int count = group.posArray.size();
                    for (int a = 0; a < count; a++) {
                        MessageObject.GroupedMessagePosition pos = group.posArray.get(a);
                        if (pos.minY != position.minY || pos.minX == position.minX && pos.maxX == position.maxX && pos.minY == position.minY && pos.maxY == position.maxY) {
                            continue;
                        }
                        if (pos.minY == position.minY) {
                            h -= (int) Math.ceil(maxHeight * pos.ph) - AndroidUtilities.dp(4);
                            break;
                        }
                    }
                    outRect.bottom = -h;
                }
            });
            albumAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @Override
                public int getItemCount() {
                    return albumMessages.size();
                }

                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                    ChatMessageCell cell = new ChatMessageCell(parent.getContext(), currentAccount);
                    cell.setDelegate(delegate);
                    cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new RecyclerListView.Holder(cell);
                }

                @Override
                public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                    ChatMessageCell cell = (ChatMessageCell) holder.itemView;
                    cell.isChat = false;
                    cell.setFullyDraw(true);
                    // Match ChatActivity's grouped-message edge flags. These are required
                    // for the album's shared caption/background to be drawn on the correct
                    // edge instead of treating every photo as an independent message.
                    cell.setMessageObject(albumMessages.get(position), albumGroup,
                            position < albumMessages.size() - 1, position > 0, false);
                }
            };
            albumList.setAdapter(albumAdapter);
            addView(albumList, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            // A normal tap opens the original channel post. Selection stays an explicit
            // action in the long-press menu, as it is in Telegram.
            setOnClickListener(v -> openPost(current));
            setOnLongClickListener(v -> {
                showPostActions(current);
                return true;
            });

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
                    } else if (TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeWebView.class) != null
                        && TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeWebView.class).url != null) {
                        handleLink(TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeWebView.class).url);
                    } else if (TLKeyboardHelper.isType(button, TL_keyboard.TL_inlineButtonTypeCallback.class)
                        || TLKeyboardHelper.isType(button, TL_keyboard.TL_inlineButtonTypeGame.class)
                        || TLKeyboardHelper.isType(button, TL_keyboard.TL_inlineButtonTypeBuy.class)
                        || TLKeyboardHelper.isType(button, TL_keyboard.TL_inlineButtonTypeUrlAuth.class)) {
                        // answered right here: popups, game links, payment forms and 2FA prompts open on top of the feed
                        SendMessagesHelper.getInstance(currentAccount).sendCallback(true, cell.getMessageObject(), button, getCallbackProxy());
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
                    if (longpress) showPostActions(current); else toggleReaction(cell.getPrimaryMessageObject(), reaction, current);
                }

                public void didPressOther(ChatMessageCell cell, float otherX, float otherY) {
                    toggleSelect(current);
                }

                public boolean hasSelectedMessages() {
                    return !selected.isEmpty();
                }

                // "Show more" on a collapsed quote / long text and quote expand: re-measure the post
                public void forceUpdate(ChatMessageCell cell, boolean anchorScroll) {
                    MessageObject mo = cell.getPrimaryMessageObject();
                    if (mo == null) return;
                    mo.forceUpdate = true;
                    if (current != null) bind(current);
                    requestLayout();
                }

                public void forceUpdate(ChatMessageCell cell, boolean anchorScroll, boolean scrollByTop) {
                    forceUpdate(cell, anchorScroll);
                }

                public void forceUpdateNoAnimation(ChatMessageCell cell, boolean anchorScroll) {
                    forceUpdate(cell, anchorScroll);
                }

                public void didPressShowMore(ChatMessageCell cell) {
                    openPost(current);
                }

                public void didPressGroupImage(ChatMessageCell cell, org.telegram.messenger.ImageReceiver imageReceiver, TLRPC.MessageExtendedMedia media, float x, float y) {
                    openPhotoViewer(cell);
                }

                public void didPressTime(ChatMessageCell cell) {
                    toggleSelect(current);
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
            dateView.setText(LocaleController.getInstance().getFormatterDay().format(post.date * 1000L)
                + (TrustedFeed.isPinned(post.chat.id, post.first().getId()) ? "  \uD83D\uDCCC Pinned" : ""));
            int n = post.messages.size();
            final boolean album = post.group != null && n > 1;
            albumList.setVisibility(album ? View.VISIBLE : View.GONE);
            cellsBox.setVisibility(album ? View.GONE : View.VISIBLE);
            if (album) {
                albumMessages.clear();
                albumMessages.addAll(post.messages);
                // same order as the chat list: newest id first (the grid is reversed like in a chat)
                Collections.sort(albumMessages, (a, b) -> b.getId() - a.getId());
                albumGroup = post.group;
                albumAdapter.notifyDataSetChanged();
                for (ChatMessageCell c : cells) c.setVisibility(View.GONE);
                return;
            }
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
