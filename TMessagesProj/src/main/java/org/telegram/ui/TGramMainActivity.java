package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.TrustedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Main TrustedGram settings page: app header with build number, categories and our channels. */
public class TGramMainActivity extends UniversalFragment {

    private static final int ID_GENERAL = 1, ID_APPEARANCE = 2, ID_FEED = 3, ID_GHOST = 4, ID_OTHER = 5, ID_FOLDERS = 6, ID_LINK = 100;

    private static final String[][] LINKS = {
        {"Updates", "trustedgram"},
        {"Releases", "trustedgramreleases"},
        {"Chat", "trustedtalks"},
        {"Powered by VTH NETWORK", "vthchannel"},
    };
    private static final int[] LINK_ICONS = {R.drawable.msg_channel, R.drawable.msg_download, R.drawable.msg_discussion, R.drawable.msg_link};

    private View headerView;

    @Override
    protected CharSequence getTitle() {
        return "TGram Settings";
    }

    private View buildHeader(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));

        // A restrained iOS-inspired dark glass surface with a subtle edge highlight.
        GradientDrawable glass = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xF0252B38, 0xF0101219});
        glass.setCornerRadius(dp(26));
        glass.setStroke(dp(1), 0x35FFFFFF);
        box.setBackground(glass);

        ImageView icon = new ImageView(context);
        GradientDrawable iconBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF35435E, 0xFF10141F});
        iconBg.setCornerRadius(dp(21));
        iconBg.setStroke(dp(1), 0x55FFFFFF);
        icon.setBackground(iconBg);
        icon.setImageResource(R.drawable.trusted_splash);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        box.addView(icon, new LinearLayout.LayoutParams(dp(66), dp(66)));

        TextView name = new TextView(context);
        name.setText("TrustedGram");
        name.setTextSize(22);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setLetterSpacing(0.01f);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(7);
        box.addView(name, nlp);

        TextView subtitle = new TextView(context);
        subtitle.setText("Your Telegram, refined.");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xFF9CA7BA);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(1);
        box.addView(subtitle, slp);

        TextView ver = new TextView(context);
        ver.setText("VERSION " + TrustedConfig.VERSION_NAME + "  •  BUILD " + TrustedConfig.VERSION_CODE);
        ver.setTextSize(10);
        ver.setTypeface(Typeface.DEFAULT_BOLD);
        ver.setLetterSpacing(0.07f);
        ver.setTextColor(0xFFB9C5D9);
        GradientDrawable versionBg = new GradientDrawable();
        versionBg.setColor(0x332F80ED);
        versionBg.setCornerRadius(dp(20));
        versionBg.setStroke(dp(1), 0x443F8FEF);
        ver.setBackground(versionBg);
        ver.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.topMargin = dp(6);
        box.addView(ver, vlp);
        return box;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (headerView == null) headerView = buildHeader(getContext());
        items.add(UItem.asCustom(headerView, 190));
        items.add(UItem.asHeader("Categories"));
        adapter.whiteSectionStart();
        items.add(UItem.asButton(ID_GENERAL, R.drawable.msg_settings, "General"));
        items.add(UItem.asButton(ID_APPEARANCE, R.drawable.msg_palette, "Appearance"));
        items.add(UItem.asButton(ID_FOLDERS, R.drawable.msg_folders, "Chat Folders"));
        items.add(UItem.asButton(ID_FEED, R.drawable.msg_channel, "Feed"));
        items.add(UItem.asButton(ID_GHOST, R.drawable.ghost, "Ghost mode"));
        items.add(UItem.asButton(ID_OTHER, R.drawable.msg_info, "Other"));
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Links"));
        adapter.whiteSectionStart();
        for (int i = 0; i < LINKS.length; i++) {
            items.add(UItem.asButton(ID_LINK + i, LINK_ICONS[i], LINKS[i][0], "@" + LINKS[i][1]));
        }
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow("TrustedGram \u2022 Powered by VTH NETWORK"));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_GENERAL: presentFragment(new TGramSettingsActivity()); return;
            case ID_APPEARANCE: presentFragment(new TGramAppearanceActivity()); return;
            case ID_FOLDERS: presentFragment(new TGramFoldersActivity()); return;
            case ID_FEED: presentFragment(new TGramFeedChannelsActivity()); return;
            case ID_GHOST: presentFragment(new TGramGhostActivity()); return;
            case ID_OTHER: presentFragment(new TGramOtherActivity()); return;
        }
        if (item.id >= ID_LINK && item.id < ID_LINK + LINKS.length) {
            MessagesController.getInstance(currentAccount).openByUserName(LINKS[item.id - ID_LINK][1], this, 1);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
