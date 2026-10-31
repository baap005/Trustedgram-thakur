package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.TrustedConfig;
import org.telegram.messenger.TrustedGhost;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** TrustedGram settings: options come from the admin panel; our channels are listed at the bottom. */
public class TGramSettingsActivity extends UniversalFragment {

    private static final int ID_CHANNEL_BASE = 1000;
    private static final int ID_TOGGLE_BASE = 100;
    private static final int ID_FEED = 20, ID_FEED_CHANNELS = 21;
    private static final int ID_GHOST = 10, ID_GHOST_READ = 11, ID_GHOST_ONLINE = 12, ID_GHOST_TYPING = 13, ID_GHOST_AFTER_SEND = 14;

    private static final String[][] CHANNELS = {
        {"Updates", "trustedgram"},
        {"Updates (Releases)", "trustedgramreleases"},
        {"Chat", "trustedtalks"},
        {"Powered by VTH NETWORK", "vthchannel"},
    };

    private final ArrayList<TrustedConfig.Item> toggles = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        toggles.addAll(TrustedConfig.getItems());
        TrustedConfig.refresh(() -> {
            toggles.clear();
            toggles.addAll(TrustedConfig.getItems());
            if (listView != null && listView.adapter != null) listView.adapter.update(true);
        });
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return "TGram Settings";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Feed"));
        items.add(UItem.asButton(ID_FEED, "Open feed", "Latest posts from your channels"));
        items.add(UItem.asButton(ID_FEED_CHANNELS, "Choose feed channels"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Ghost mode"));
        items.add(UItem.asButtonCheck(ID_GHOST, "Ghost mode", "Hide read receipts, online status and typing in one tap").setChecked(TrustedGhost.isActive()));
        items.add(UItem.asButtonCheck(ID_GHOST_READ, "Don't send read receipts", "Chats stay unread for others while you read them").setChecked(TrustedGhost.isBlockRead()));
        items.add(UItem.asButtonCheck(ID_GHOST_ONLINE, "Don't send online status", "You always appear offline").setChecked(TrustedGhost.isHideOnline()));
        items.add(UItem.asButtonCheck(ID_GHOST_TYPING, "Don't send typing status", "Hides typing, recording and upload status").setChecked(TrustedGhost.isHideTyping()));
        items.add(UItem.asButtonCheck(ID_GHOST_AFTER_SEND, "Mark as read after I send", "Replying to a chat marks it read, like normal").setChecked(TrustedGhost.isReadAfterSend()));
        items.add(UItem.asShadow(null));
        if (!toggles.isEmpty()) {
            items.add(UItem.asHeader("TrustedGram features"));
            for (int i = 0; i < toggles.size(); i++) {
                TrustedConfig.Item t = toggles.get(i);
                items.add(UItem.asButtonCheck(ID_TOGGLE_BASE + i, t.title, t.desc).setChecked(TrustedConfig.isOn(t.key)));
            }
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asHeader("Join us"));
        for (int i = 0; i < CHANNELS.length; i++) {
            items.add(UItem.asButton(ID_CHANNEL_BASE + i, CHANNELS[i][0], "@" + CHANNELS[i][1]));
        }
        items.add(UItem.asShadow("TrustedGram \u2022 Powered by VTH NETWORK"));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_FEED) {
            presentFragment(new TGramFeedActivity());
            return;
        }
        if (item.id == ID_FEED_CHANNELS) {
            presentFragment(new TGramFeedChannelsActivity());
            return;
        }
        if (item.id >= ID_GHOST && item.id <= ID_GHOST_AFTER_SEND) {
            switch (item.id) {
                case ID_GHOST: TrustedGhost.setActive(!TrustedGhost.isActive()); break;
                case ID_GHOST_READ: TrustedGhost.setBlockRead(!TrustedGhost.isBlockRead()); break;
                case ID_GHOST_ONLINE: TrustedGhost.setHideOnline(!TrustedGhost.isHideOnline()); break;
                case ID_GHOST_TYPING: TrustedGhost.setHideTyping(!TrustedGhost.isHideTyping()); break;
                case ID_GHOST_AFTER_SEND: TrustedGhost.setReadAfterSend(!TrustedGhost.isReadAfterSend()); break;
            }
            listView.adapter.update(true);
            return;
        }
        if (item.id >= ID_CHANNEL_BASE && item.id < ID_CHANNEL_BASE + CHANNELS.length) {
            MessagesController.getInstance(currentAccount).openByUserName(CHANNELS[item.id - ID_CHANNEL_BASE][1], this, 1);
        } else if (item.id >= ID_TOGGLE_BASE && item.id < ID_TOGGLE_BASE + toggles.size()) {
            String key = toggles.get(item.id - ID_TOGGLE_BASE).key;
            TrustedConfig.setOn(key, !TrustedConfig.isOn(key));
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
