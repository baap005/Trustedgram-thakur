package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.TrustedAppearance;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** TrustedGram Appearance: chat list title, stories row, floating button. Applies when the chat list is opened again. */
public class TGramAppearanceActivity extends UniversalFragment {

    private static final int ID_TITLE = 1, ID_STORIES = 2, ID_FAB = 3;

    @Override
    protected CharSequence getTitle() {
        return "Appearance";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Chat List"));
        items.add(UItem.asButton(ID_TITLE, "Title Text", TrustedAppearance.TITLE_NAMES[TrustedAppearance.getTitleMode()]));
        items.add(UItem.asButtonCheck(ID_STORIES, "Hide Stories", "Remove the stories row above your chats").setChecked(TrustedAppearance.isHideStories()));
        items.add(UItem.asButtonCheck(ID_FAB, "Hide Floating Button", "Remove the new message button").setChecked(TrustedAppearance.isHideFloating()));
        items.add(UItem.asShadow("Changes apply the next time the chat list opens."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_TITLE) {
            TrustedAppearance.setTitleMode((TrustedAppearance.getTitleMode() + 1) % TrustedAppearance.TITLE_NAMES.length);
        } else if (item.id == ID_STORIES) {
            TrustedAppearance.setHideStories(!TrustedAppearance.isHideStories());
        } else if (item.id == ID_FAB) {
            TrustedAppearance.setHideFloating(!TrustedAppearance.isHideFloating());
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
