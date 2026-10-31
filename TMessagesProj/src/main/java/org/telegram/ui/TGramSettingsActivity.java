package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.TrustedConfig;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** General: switches that are added and managed from the admin panel. */
public class TGramSettingsActivity extends UniversalFragment {

    private static final int ID_TOGGLE_BASE = 100;
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
        return "General";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (toggles.isEmpty()) {
            items.add(UItem.asShadow("More options will appear here when they are added."));
            return;
        }
        items.add(UItem.asHeader("TrustedGram features"));
        for (int i = 0; i < toggles.size(); i++) {
            TrustedConfig.Item t = toggles.get(i);
            items.add(UItem.asButtonCheck(ID_TOGGLE_BASE + i, t.title, t.desc).setChecked(TrustedConfig.isOn(t.key)));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_TOGGLE_BASE && item.id < ID_TOGGLE_BASE + toggles.size()) {
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
