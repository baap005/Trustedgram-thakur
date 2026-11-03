package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.EditText;

import org.telegram.messenger.R;
import org.telegram.messenger.TrustedConfig;
import org.telegram.messenger.TrustedFeed;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Feed Settings, laid out like exteraGram: a General card, then "Shown in feed" and "Hidden" cards listing every
 * channel with its picture and a round check. Search icon filters the channels, the three-dot menu includes or
 * excludes all of them.
 */
public class TGramFeedChannelsActivity extends UniversalFragment {

    @Override
    protected boolean isTGramPage() {
        return true;
    }

    private static final int ID_WIDE = 1, ID_UNREAD = 2, ID_ARCHIVE = 3, ID_TAB = 4;
    private static final int MENU_SEARCH = 10, MENU_MORE = 11, MENU_ALL = 12, MENU_NONE = 13;
    private static final int ID_BASE = 1000;

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private String query = "";

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
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem search = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchCollapse() {
                query = "";
                if (listView != null) listView.adapter.update(true);
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString().trim().toLowerCase();
                if (listView != null) listView.adapter.update(true);
            }
        });
        search.setSearchFieldHint("Search channels");
        ActionBarMenuItem more = menu.addItem(MENU_MORE, R.drawable.ic_ab_other);
        more.addSubItem(MENU_ALL, R.drawable.msg_markread, "Include all channels");
        more.addSubItem(MENU_NONE, R.drawable.msg_cancel, "Exclude all channels");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ALL || id == MENU_NONE) {
                    TrustedFeed.setAll(currentAccount, id == MENU_ALL);
                    listView.adapter.update(true);
                }
            }
        });
        return view;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean searching = !query.isEmpty();
        if (!searching) {
            items.add(UItem.asHeader("General"));
            items.add(UItem.asButtonCheck(ID_TAB, "Show in Bottom Tabs", "Show the feed as a bottom navigation tab.").setChecked(TrustedConfig.isFeedTab()));
            items.add(UItem.asButtonCheck(ID_WIDE, "Wide Posts in Feed", null).setChecked(TrustedConfig.isFeedWide()));
            items.add(UItem.asButtonCheck(ID_UNREAD, "Unread Post Counter", null).setChecked(TrustedConfig.isFeedUnreadCounter()));
            items.add(UItem.asButtonCheck(ID_ARCHIVE, "Show Chats From Archive", null).setChecked(TrustedConfig.isFeedArchive()));
            items.add(UItem.asShadow("Also include channels from your archived chats in the feed."));
        }
        ArrayList<Integer> shown = new ArrayList<>();
        ArrayList<Integer> hidden = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat c = channels.get(i);
            if (searching && (c.title == null || !c.title.toLowerCase().contains(query))) continue;
            (TrustedFeed.isIncluded(c.id) ? shown : hidden).add(i);
        }
        if (!shown.isEmpty()) {
            items.add(UItem.asHeader("Shown in feed"));
            for (int i : shown) items.add(UItem.asUserCheckbox(ID_BASE + i, channels.get(i)).pad().setChecked(true));
            items.add(UItem.asShadow(null));
        }
        if (!hidden.isEmpty()) {
            items.add(UItem.asHeader("Hidden"));
            for (int i : hidden) items.add(UItem.asUserCheckbox(ID_BASE + i, channels.get(i)).pad().setChecked(false));
            items.add(UItem.asShadow(null));
        }
        if (shown.isEmpty() && hidden.isEmpty()) {
            items.add(UItem.asShadow(searching ? "Nothing found." : "You haven't joined any channels yet."));
        } else {
            items.add(UItem.asShadow("Uncheck a channel to hide its posts from the feed. New channels are shown automatically."));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_TAB) {
            TrustedConfig.setFeedTab(!TrustedConfig.isFeedTab());
            showRestartRequired();
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
            TrustedFeed.dirty = true;
        } else if (item.id >= ID_BASE && item.id - ID_BASE < channels.size()) {
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
