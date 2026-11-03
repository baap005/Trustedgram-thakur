package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.TrustedGhost;
import org.telegram.messenger.TrustedConfig;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class TGramGhostActivity extends UniversalFragment {

    @Override
    protected boolean isTGramPage() {
        return true;
    }

    private static final int ID_GHOST = 10, ID_READ = 11, ID_ONLINE = 12, ID_TYPING = 13, ID_AFTER_SEND = 14;

    @Override
    protected boolean useLargeTitle() {
        return TrustedConfig.isLargeTitles();
    }

    @Override
    protected CharSequence getTitle() {
        return "Ghost mode";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButtonCheck(ID_GHOST, "Ghost mode", "Hide read receipts, online status and typing in one tap").setChecked(TrustedGhost.isActive()));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Options"));
        items.add(UItem.asButtonCheck(ID_READ, "Don't send read receipts", "Chats stay unread for others while you read them").setChecked(TrustedGhost.isBlockRead()));
        items.add(UItem.asButtonCheck(ID_ONLINE, "Don't send online status", "You always appear offline").setChecked(TrustedGhost.isHideOnline()));
        items.add(UItem.asButtonCheck(ID_TYPING, "Don't send typing status", "Hides typing, recording and upload status").setChecked(TrustedGhost.isHideTyping()));
        items.add(UItem.asButtonCheck(ID_AFTER_SEND, "Mark as read after I send", "Replying to a chat marks it read, like normal").setChecked(TrustedGhost.isReadAfterSend()));
        items.add(UItem.asShadow("Messages you read in ghost mode may show as unread again after a restart."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_GHOST: TrustedGhost.setActive(!TrustedGhost.isActive()); break;
            case ID_READ: TrustedGhost.setBlockRead(!TrustedGhost.isBlockRead()); break;
            case ID_ONLINE: TrustedGhost.setHideOnline(!TrustedGhost.isHideOnline()); break;
            case ID_TYPING: TrustedGhost.setHideTyping(!TrustedGhost.isHideTyping()); break;
            case ID_AFTER_SEND: TrustedGhost.setReadAfterSend(!TrustedGhost.isReadAfterSend()); break;
            default: return;
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
