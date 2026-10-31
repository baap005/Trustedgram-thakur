package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.TrustedConfig;
import org.telegram.messenger.TrustedFeed;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Feed settings: layout options and which joined channels are shown in the feed. */
public class TGramFeedChannelsActivity extends UniversalFragment {

    private static final int ID_OPEN = 1, ID_WIDE = 2, ID_ARCHIVE = 3, ID_ALL = 4, ID_NONE = 5, ID_BASE = 100;
    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        channels.addAll(TrustedFeed.getChannels(currentAccount));
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return "Feed Settings";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_OPEN, "Open feed"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("General"));
        items.add(UItem.asButtonCheck(ID_WIDE, "Wide Posts in Feed", "Posts use the full width of the screen").setChecked(TrustedConfig.isFeedWide()));
        items.add(UItem.asButtonCheck(ID_ARCHIVE, "Show Chats From Archive", "Also include channels from your archived chats in the feed.").setChecked(TrustedConfig.isFeedArchive()));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Shown in feed"));
        if (channels.isEmpty()) {
            items.add(UItem.asShadow("You haven't joined any channels yet."));
            return;
        }
        items.add(UItem.asButton(ID_ALL, "Include all channels"));
        items.add(UItem.asButton(ID_NONE, "Exclude all channels"));
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat c = channels.get(i);
            String username = ChatObject.getPublicUsername(c);
            items.add(UItem.asButtonCheck(ID_BASE + i, c.title, username != null ? "@" + username : "Private channel")
                .setChecked(TrustedFeed.isIncluded(c.id)));
        }
        items.add(UItem.asShadow("Uncheck a channel to hide its posts from the feed. New channels are shown automatically."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_OPEN) {
            presentFragment(new TGramFeedActivity());
            return;
        } else if (item.id == ID_WIDE) {
            TrustedConfig.setFeedWide(!TrustedConfig.isFeedWide());
            TrustedFeed.dirty = true;
        } else if (item.id == ID_ARCHIVE) {
            TrustedConfig.setFeedArchive(!TrustedConfig.isFeedArchive());
            channels.clear();
            channels.addAll(TrustedFeed.getChannels(currentAccount));
        } else if (item.id == ID_ALL || item.id == ID_NONE) {
            TrustedFeed.setAll(currentAccount, item.id == ID_ALL);
        } else if (item.id >= ID_BASE && item.id < ID_BASE + channels.size()) {
            long id = channels.get(item.id - ID_BASE).id;
            TrustedFeed.setIncluded(id, !TrustedFeed.isIncluded(id));
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
