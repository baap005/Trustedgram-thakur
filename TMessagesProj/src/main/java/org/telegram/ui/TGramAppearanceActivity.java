package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.TrustedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class TGramAppearanceActivity extends UniversalFragment {

    private static final int ID_MODE = 1, ID_CUSTOM = 2, ID_CENTER = 3, ID_BLUR = 4;
    private static final String[] MODES = {"App name", "Account name", "Custom text"};

    @Override
    protected CharSequence getTitle() {
        return "Appearance";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Chat list"));
        items.add(UItem.asButton(ID_MODE, "Title", MODES[Math.max(0, Math.min(2, TrustedConfig.getTitleMode()))]));
        items.add(UItem.asButton(ID_CUSTOM, "Custom title text", TrustedConfig.getTitleText()));
        items.add(UItem.asButtonCheck(ID_CENTER, "Center Title", "Show the title in the middle of the top bar").setChecked(TrustedConfig.isTitleCentered()));
        items.add(UItem.asShadow("Title changes apply the next time you open the app."));
        items.add(UItem.asHeader("Popups"));
        items.add(UItem.asButtonCheck(ID_BLUR, "Glass blur", "Frosted background behind TrustedGram popups (Android 12+)").setChecked(TrustedConfig.isGlassBlur()));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_BLUR) {
            TrustedConfig.setGlassBlur(!TrustedConfig.isGlassBlur());
            listView.adapter.update(true);
        } else if (item.id == ID_CENTER) {
            TrustedConfig.setTitleCentered(!TrustedConfig.isTitleCentered());
            listView.adapter.update(true);
        } else if (item.id == ID_MODE) {
            if (getParentActivity() == null) return;
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle("Title");
            b.setItems(MODES, (d, which) -> {
                TrustedConfig.setTitleMode(which);
                listView.adapter.update(true);
            });
            b.setNegativeButton("Cancel", null);
            showDialog(b.create());
        } else if (item.id == ID_CUSTOM) {
            if (getParentActivity() == null) return;
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle("Custom title text");
            final EditText et = new EditText(getParentActivity());
            et.setText(TrustedConfig.getTitleText());
            et.setSingleLine(true);
            et.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            FrameLayout box = new FrameLayout(getParentActivity());
            box.setPadding(dp(24), dp(8), dp(24), 0);
            box.addView(et);
            b.setView(box);
            b.setPositiveButton("Save", (d, w) -> {
                TrustedConfig.setTitleText(et.getText().toString());
                TrustedConfig.setTitleMode(2);
                listView.adapter.update(true);
            });
            b.setNegativeButton("Cancel", null);
            showDialog(b.create());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
