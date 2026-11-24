package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.TrustedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** General: profile ID/DC, chat list clean-up options. */
public class TGramSettingsActivity extends UniversalFragment {

    @Override
    protected boolean isTGramPage() {
        return true;
    }

    private static final int ID_IDDC = 1, ID_STORIES = 2, ID_FAB = 3, ID_RELATIVE_LAST_SEEN = 4, ID_TIME_SECONDS = 5;
    private static final String[] IDDC = {"Hide", "Telegram API", "Bot API"};

    @Override
    protected boolean useLargeTitle() {
        return TrustedConfig.isLargeTitles();
    }

    @Override
    protected CharSequence getTitle() {
        return "General";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Profile"));
        items.add(UItem.asButton(ID_IDDC, "Show ID and DC", IDDC[Math.max(0, Math.min(2, TrustedConfig.getIdDcMode()))]));
        items.add(UItem.asShadow("Shows the id and data center of users, bots and channels in their profile."));
        items.add(UItem.asButtonCheck(ID_RELATIVE_LAST_SEEN, "Relative Last Seen", "Show recent activity as time elapsed, such as 5 minutes ago").setChecked(TrustedConfig.isRelativeLastSeen()));
        items.add(UItem.asButtonCheck(ID_TIME_SECONDS, "Show Seconds in Time", "Display timestamps with seconds, for example 12:34:56").setChecked(TrustedConfig.isTimeWithSeconds()));
        items.add(UItem.asHeader("Chat List"));
        items.add(UItem.asButtonCheck(ID_STORIES, "Hide Stories", "Remove the stories row above your chats").setChecked(TrustedConfig.isHideStories()));
        items.add(UItem.asButtonCheck(ID_FAB, "Hide Floating Button", "Remove the new message button").setChecked(TrustedConfig.isHideFloatingButton()));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_STORIES) {
            TrustedConfig.setHideStories(!TrustedConfig.isHideStories());
            showRestartRequired();
        } else if (item.id == ID_FAB) {
            TrustedConfig.setHideFloatingButton(!TrustedConfig.isHideFloatingButton());
            showRestartRequired();
        } else if (item.id == ID_RELATIVE_LAST_SEEN) {
            TrustedConfig.setRelativeLastSeen(!TrustedConfig.isRelativeLastSeen());
        } else if (item.id == ID_TIME_SECONDS) {
            TrustedConfig.setTimeWithSeconds(!TrustedConfig.isTimeWithSeconds());
        } else if (item.id == ID_IDDC) {
            if (getParentActivity() == null) return;
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle("Show ID and DC");
            b.setItems(IDDC, (d, which) -> {
                TrustedConfig.setIdDcMode(which);
                listView.adapter.update(true);
            });
            b.setNegativeButton("Cancel", null);
            showDialog(b.create());
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
