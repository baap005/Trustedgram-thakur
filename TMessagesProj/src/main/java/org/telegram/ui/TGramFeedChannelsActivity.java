package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.TrustedFeed;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Lets the user choose which joined channels appear in the feed. */
public class TGramFeedChannelsActivity extends UniversalFragment {

    private static final int ID_ALL = 1, ID_NONE = 2, ID_BASE = 100;
    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        channels.addAll(TrustedFeed.getChannels(currentAccount));
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return "Feed channels";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_ALL, "Include all channels"));
        items.add(UItem.asButton(ID_NONE, "Exclude all channels"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Your channels"));
        if (channels.isEmpty()) {
            items.add(UItem.asShadow("You haven't joined any channels yet."));
            return;
        }
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat c = channels.get(i);
            String username = ChatObject.getPublicUsername(c);
            items.add(UItem.asButtonCheck(ID_BASE + i, c.title, username != null ? "@" + username : "Private channel")
                .setChecked(TrustedFeed.isIncluded(c.id)));
        }
        items.add(UItem.asShadow("Only included channels show up in your feed."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ALL || item.id == ID_NONE) {
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
