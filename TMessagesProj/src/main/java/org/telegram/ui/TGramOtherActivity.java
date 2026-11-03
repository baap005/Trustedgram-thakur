package org.telegram.ui;

import android.widget.Toast;
import android.view.View;

import org.telegram.messenger.TrustedConfig;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class TGramOtherActivity extends UniversalFragment {

    @Override
    protected boolean isTGramPage() {
        return true;
    }

    private static final int ID_VERSION = 1, ID_CHECK = 2;

    @Override
    protected boolean useLargeTitle() {
        return TrustedConfig.isLargeTitles();
    }

    @Override
    protected CharSequence getTitle() {
        return "Other";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("About"));
        items.add(UItem.asButton(ID_VERSION, "Build number", TrustedConfig.VERSION_NAME + " (" + TrustedConfig.VERSION_CODE + ")"));
        items.add(UItem.asButton(ID_CHECK, "Check for updates"));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_CHECK && getParentActivity() != null) {
            Toast.makeText(getParentActivity(), "Checking for updates...", Toast.LENGTH_SHORT).show();
            TrustedConfig.checkNow(getParentActivity());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
