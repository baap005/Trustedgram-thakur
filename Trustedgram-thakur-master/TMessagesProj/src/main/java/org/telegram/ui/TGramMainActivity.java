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
        ImageView icon = new ImageView(context);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF232A3C, 0xFF000000});
        bg.setCornerRadius(dp(24));
        icon.setBackground(bg);
        icon.setImageResource(R.drawable.trusted_splash);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.addView(icon, new LinearLayout.LayoutParams(dp(92), dp(92)));
        TextView name = new TextView(context);
        name.setText("TrustedGram");
        name.setTextSize(22);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(12);
        box.addView(name, nlp);
        TextView ver = new TextView(context);
        ver.setText("Version " + TrustedConfig.VERSION_NAME + " (build " + TrustedConfig.VERSION_CODE + ")");
        ver.setTextSize(15);
        ver.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        box.addView(ver, new LinearLayout.LayoutParams(-2, -2));
        return box;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (headerView == null) headerView = buildHeader(getContext());
        items.add(UItem.asCustom(headerView, 190));
        items.add(UItem.asHeader("Categories"));
        items.add(UItem.asButton(ID_GENERAL, R.drawable.msg_settings, "General"));
        items.add(UItem.asButton(ID_APPEARANCE, R.drawable.msg_palette, "Appearance"));
        items.add(UItem.asButton(ID_FOLDERS, R.drawable.msg_folders, "Chat Folders"));
        items.add(UItem.asButton(ID_FEED, R.drawable.msg_channel, "Feed"));
        items.add(UItem.asButton(ID_GHOST, R.drawable.ghost, "Ghost mode"));
        items.add(UItem.asButton(ID_OTHER, R.drawable.msg_info, "Other"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Links"));
        for (int i = 0; i < LINKS.length; i++) {
            items.add(UItem.asButton(ID_LINK + i, LINK_ICONS[i], LINKS[i][0], "@" + LINKS[i][1]));
        }
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
