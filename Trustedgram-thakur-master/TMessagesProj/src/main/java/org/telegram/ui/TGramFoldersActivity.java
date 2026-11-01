package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Chat Folders: every folder in one list, tap to edit it, create a new one or open Telegram's folder manager. */
public class TGramFoldersActivity extends UniversalFragment {

    private static final int ID_NEW = 1, ID_MANAGE = 2, ID_BASE = 100;

    @Override
    public boolean onFragmentCreate() {
        getMessagesController().loadRemoteFilters(true);
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) listView.adapter.update(false);
    }

    @Override
    protected CharSequence getTitle() {
        return "Chat Folders";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        ArrayList<MessagesController.DialogFilter> folders = getMessagesController().dialogFilters;
        items.add(UItem.asHeader("Your folders"));
        int shown = 0;
        for (int i = 0; i < folders.size(); i++) {
            MessagesController.DialogFilter f = folders.get(i);
            if (f == null || f.isDefault()) continue;
            String name = f.name == null || f.name.trim().isEmpty() ? "Folder" : f.name;
            items.add(UItem.asButton(ID_BASE + i, R.drawable.msg_folders, name));
            shown++;
        }
        if (shown == 0) items.add(UItem.asShadow("You have no folders yet."));
        items.add(UItem.asButton(ID_NEW, R.drawable.msg_add, "Create new folder"));
        items.add(UItem.asShadow("Tap a folder to change its chats, name and icon."));
        items.add(UItem.asButton(ID_MANAGE, R.drawable.msg_folders, "Manage folders"));
        items.add(UItem.asShadow("Reorder, delete and recommended folders."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_NEW) {
            presentFragment(new FilterCreateActivity());
        } else if (item.id == ID_MANAGE) {
            presentFragment(new FiltersSetupActivity());
        } else if (item.id >= ID_BASE) {
            ArrayList<MessagesController.DialogFilter> folders = getMessagesController().dialogFilters;
            int index = item.id - ID_BASE;
            if (index >= 0 && index < folders.size()) presentFragment(new FilterCreateActivity(folders.get(index)));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
