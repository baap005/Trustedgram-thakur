package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.TrustedBadges;
import org.telegram.messenger.TrustedFeed;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Feed Settings: display options + which joined channels appear in the feed. */
public class TGramFeedChannelsActivity extends UniversalFragment {

    private static final int ID_WIDE = 1, ID_UNREAD = 2, ID_ARCHIVE = 3, ID_COMPACT = 4, ID_NEWEST = 5;
    private static final int ID_ALL = 10, ID_NONE = 11, ID_BASE = 100;
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
        items.add(UItem.asHeader("General"));
        items.add(UItem.asButtonCheck(ID_WIDE, "Wide Posts in Feed", "Posts use the full screen width").setChecked(TrustedFeed.isWidePosts()));
        items.add(UItem.asButtonCheck(ID_UNREAD, "Unread Post Counter", "Show how many posts are unread at the top of the feed").setChecked(TrustedFeed.isUnreadCounter()));
        items.add(UItem.asButtonCheck(ID_COMPACT, "Compact Posts", "Show only the media when a post has a photo or video").setChecked(TrustedFeed.isCompact()));
        items.add(UItem.asButtonCheck(ID_NEWEST, "Newest First", "Turn off to read oldest first, like a chat").setChecked(TrustedFeed.isNewestFirst()));
        items.add(UItem.asButtonCheck(ID_ARCHIVE, "Show Chats From Archive", "Also include channels from your archived chats in the feed").setChecked(TrustedFeed.isShowArchive()));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Shown in feed"));
        items.add(UItem.asButton(ID_ALL, "Select all channels"));
        items.add(UItem.asButton(ID_NONE, "Deselect all channels"));
        if (channels.isEmpty()) {
            items.add(UItem.asShadow("You haven't joined any channels yet."));
            return;
        }
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat c = channels.get(i);
            String username = ChatObject.getPublicUsername(c);
            String sub = (username != null ? "@" + username : "Private channel") + (TrustedBadges.isVerified(c) ? "  \u2022  Verified" : "");
            items.add(UItem.asButtonCheck(ID_BASE + i, c.title, sub).setChecked(TrustedFeed.isIncluded(c.id)));
        }
        items.add(UItem.asShadow("Uncheck a channel to hide its posts from the feed. New channels are shown automatically."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_WIDE: TrustedFeed.setWidePosts(!TrustedFeed.isWidePosts()); break;
            case ID_UNREAD: TrustedFeed.setUnreadCounter(!TrustedFeed.isUnreadCounter()); break;
            case ID_COMPACT: TrustedFeed.setCompact(!TrustedFeed.isCompact()); break;
            case ID_NEWEST: TrustedFeed.setNewestFirst(!TrustedFeed.isNewestFirst()); break;
            case ID_ARCHIVE:
                TrustedFeed.setShowArchive(!TrustedFeed.isShowArchive());
                channels.clear();
                channels.addAll(TrustedFeed.getChannels(currentAccount));
                break;
            case ID_ALL: TrustedFeed.setAll(currentAccount, true); break;
            case ID_NONE: TrustedFeed.setAll(currentAccount, false); break;
            default:
                if (item.id >= ID_BASE && item.id < ID_BASE + channels.size()) {
                    long id = channels.get(item.id - ID_BASE).id;
                    TrustedFeed.setIncluded(id, !TrustedFeed.isIncluded(id));
                } else {
                    return;
                }
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
