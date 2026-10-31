package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import org.telegram.messenger.TrustedConfig;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.TrustedFeed;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/** One combined list of the newest posts from the channels the user chose. Tap a post to open it in its channel. */
public class TGramFeedActivity extends BaseFragment {

    private static final int PER_CHANNEL = 6;
    private static final int MAX_CHANNELS = 30;
    private static final int MAX_POSTS = 80;
    private static final int MENU_CHANNELS = 1, MENU_REFRESH = 2;

    private static class Post {
        MessageObject message;
        TLRPC.Chat chat;
    }

    private final ArrayList<Post> posts = new ArrayList<>();
    private LinearLayout list;
    private TextView statusView;
    private int loadGeneration;
    private int pending;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Feed");
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_REFRESH, R.drawable.msg_retry);
        menu.addItem(MENU_CHANNELS, R.drawable.msg_customize);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == MENU_CHANNELS) presentFragment(new TGramFeedChannelsActivity());
                else if (id == MENU_REFRESH) load();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(12));
        scroll.addView(list, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
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

    private void load() {
        TrustedFeed.dirty = false;
        final int account = currentAccount;
        ArrayList<TLRPC.Chat> channels = new ArrayList<>();
        for (TLRPC.Chat c : TrustedFeed.getChannels(account)) {
            if (TrustedFeed.isIncluded(c.id) && channels.size() < MAX_CHANNELS) channels.add(c);
        }
        actionBar.setSubtitle(channels.size() + (channels.size() == 1 ? " channel" : " channels"));
        final int generation = ++loadGeneration;
        posts.clear();
        list.removeAllViews();
        if (channels.isEmpty()) {
            statusView.setText("Your feed is empty.\nTap the settings icon at the top to choose channels.");
            statusView.setVisibility(View.VISIBLE);
            return;
        }
        statusView.setText("Loading feed...");
        statusView.setVisibility(View.VISIBLE);
        pending = channels.size();
        for (TLRPC.Chat chat : channels) {
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
                        p.message = new MessageObject(account, m, users, chats, false, false);
                        p.chat = chat;
                        posts.add(p);
                    }
                }
                if (--pending == 0) render();
            }));
        }
    }

    private void render() {
        list.removeAllViews();
        Collections.sort(posts, (a, b) -> b.message.messageOwner.date - a.message.messageOwner.date);
        if (posts.isEmpty()) {
            statusView.setText("No posts found.");
            return;
        }
        statusView.setVisibility(View.GONE);
        Context context = getContext();
        for (int i = 0; i < posts.size() && i < MAX_POSTS; i++) {
            int side = TrustedConfig.isFeedWide() ? 6 : 24;
            list.addView(makePostView(context, posts.get(i)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, side, 4, side, 4));
        }
    }

    private View makePostView(Context context, Post post) {
        final MessageObject mo = post.message;
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(Theme.getColor(Theme.key_chat_inBubble));
        bubble.setCornerRadius(dp(18));
        card.setBackground(bubble);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(20));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(post.chat);
        avatar.setForUserOrChat(post.chat, avatarDrawable);
        header.addView(avatar, LayoutHelper.createLinear(40, 40));
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn));
        name.setText(post.chat.title);
        names.addView(name);
        TextView date = new TextView(context);
        date.setTextSize(13);
        date.setTextColor(Theme.getColor(Theme.key_chat_inTimeText));
        date.setText(LocaleController.formatDateChat(mo.messageOwner.date));
        names.addView(date);
        header.addView(names, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 0, 0));
        card.addView(header);

        String text = mo.messageOwner.message;
        if (TextUtils.isEmpty(text)) {
            text = mo.isVideo() ? "Video" : mo.isPhoto() ? "Photo" : mo.messageOwner.media != null ? "Media" : "";
        }
        if (!TextUtils.isEmpty(text)) {
            TextView body = new TextView(context);
            body.setTextSize(16);
            body.setMaxLines(10);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn));
            body.setText(text);
            card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        }

        if (mo.photoThumbs != null && !mo.photoThumbs.isEmpty() && (mo.isPhoto() || mo.isVideo())) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(mo.photoThumbs, AndroidUtilities.getPhotoSize());
            if (size != null) {
                BackupImageView photo = new BackupImageView(context);
                photo.setRoundRadius(dp(8));
                photo.setImage(ImageLocation.getForObject(size, mo.photoThumbsObject), "600_400", (Drawable) null, mo);
                card.addView(photo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200, 0, 10, 0, 0));
            }
        }

        if (mo.messageOwner.views > 0) {
            TextView views = new TextView(context);
            views.setTextSize(13);
            views.setTextColor(Theme.getColor(Theme.key_chat_inTimeText));
            views.setText(LocaleController.formatShortNumber(mo.messageOwner.views, null) + " views");
            card.addView(views, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }

        card.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putLong("chat_id", post.chat.id);
            args.putInt("message_id", mo.getId());
            presentFragment(new ChatActivity(args));
        });
        return card;
    }
}
