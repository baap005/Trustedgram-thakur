package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.TrustedConfig;
import org.telegram.messenger.TrustedFeed;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Feed settings: layout options and which joined channels are shown in the feed (each channel with its picture). */
public class TGramFeedChannelsActivity extends UniversalFragment {

    private static final int ID_OPEN = 1, ID_WIDE = 2, ID_ARCHIVE = 3, ID_ALL = 4, ID_NONE = 5, ID_UNREAD = 6, ID_LIST = 7;
    private static final int ROW_DP = 60;

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private final ArrayList<CheckDot> dots = new ArrayList<>();
    private View listCard;

    @Override
    public boolean onFragmentCreate() {
        channels.addAll(TrustedFeed.getChannels(currentAccount));
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return "Feed Settings";
    }

    /** Round blue check like the one in the Telegram forward picker, drawn by hand. */
    private static class CheckDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        boolean on;

        CheckDot(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(11);
            paint.setStyle(Paint.Style.FILL);
            if (on) {
                paint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                canvas.drawCircle(cx, cy, r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xFFFFFFFF);
                paint.setStrokeWidth(dp(2));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                check.reset();
                check.moveTo(cx - dp(5), cy);
                check.lineTo(cx - dp(1.5f), cy + dp(3.5f));
                check.lineTo(cx + dp(5), cy - dp(3.5f));
                canvas.drawPath(check, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(Theme.getColor(Theme.key_checkbox) & 0x99FFFFFF);
                canvas.drawCircle(cx, cy, r - dp(1), paint);
            }
        }
    }

    private View buildChannelCard(Context context) {
        dots.clear();
        FrameLayout wrap = new FrameLayout(context);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundWhite)));
        wrap.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, channels.size() * ROW_DP, Gravity.TOP, 16, 0, 16, 0));
        for (int i = 0; i < channels.size(); i++) {
            final TLRPC.Chat chat = channels.get(i);
            FrameLayout row = new FrameLayout(context);
            row.setBackground(Theme.getSelectorDrawable(false));

            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(22));
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
            row.addView(avatar, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(context);
            name.setTextSize(16);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setText(chat.title);
            texts.addView(name);
            TextView sub = new TextView(context);
            sub.setTextSize(13);
            sub.setSingleLine(true);
            sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            String username = ChatObject.getPublicUsername(chat);
            sub.setText(username != null ? "@" + username : "Private channel");
            texts.addView(sub);
            row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 70, 0, 52, 0));

            CheckDot dot = new CheckDot(context);
            dot.on = TrustedFeed.isIncluded(chat.id);
            dots.add(dot);
            row.addView(dot, LayoutHelper.createFrame(28, 28, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            row.setOnClickListener(v -> {
                boolean now = !TrustedFeed.isIncluded(chat.id);
                TrustedFeed.setIncluded(chat.id, now);
                dot.on = now;
                dot.invalidate();
            });
            card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, ROW_DP));
        }
        return wrap;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_OPEN, "Open feed"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("General"));
        items.add(UItem.asButtonCheck(ID_WIDE, "Wide Posts in Feed", "Posts use the full width of the screen").setChecked(TrustedConfig.isFeedWide()));
        items.add(UItem.asButtonCheck(ID_UNREAD, "Unread Post Counter", "Show how many posts are unread at the top of the feed").setChecked(TrustedConfig.isFeedUnreadCounter()));
        items.add(UItem.asButtonCheck(ID_ARCHIVE, "Show Chats From Archive", "Also include channels from your archived chats in the feed.").setChecked(TrustedConfig.isFeedArchive()));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Shown in feed"));
        if (channels.isEmpty()) {
            items.add(UItem.asShadow("You haven't joined any channels yet."));
            return;
        }
        items.add(UItem.asButton(ID_ALL, "Include all channels"));
        items.add(UItem.asButton(ID_NONE, "Exclude all channels"));
        if (listCard == null || listCard.getContext() != getContext()) listCard = buildChannelCard(getContext());
        items.add(UItem.asCustom(listCard, channels.size() * ROW_DP));
        items.add(UItem.asShadow("Tap a channel to show or hide its posts in the feed. New channels are shown automatically."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_OPEN) {
            presentFragment(new TGramFeedActivity());
            return;
        } else if (item.id == ID_WIDE) {
            TrustedConfig.setFeedWide(!TrustedConfig.isFeedWide());
            TrustedFeed.dirty = true;
        } else if (item.id == ID_UNREAD) {
            TrustedConfig.setFeedUnreadCounter(!TrustedConfig.isFeedUnreadCounter());
            TrustedFeed.dirty = true;
        } else if (item.id == ID_ARCHIVE) {
            TrustedConfig.setFeedArchive(!TrustedConfig.isFeedArchive());
            channels.clear();
            channels.addAll(TrustedFeed.getChannels(currentAccount));
            listCard = null;
        } else if (item.id == ID_ALL || item.id == ID_NONE) {
            TrustedFeed.setAll(currentAccount, item.id == ID_ALL);
            for (int i = 0; i < dots.size() && i < channels.size(); i++) {
                dots.get(i).on = item.id == ID_ALL;
                dots.get(i).invalidate();
            }
            return;
        } else {
            return;
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
