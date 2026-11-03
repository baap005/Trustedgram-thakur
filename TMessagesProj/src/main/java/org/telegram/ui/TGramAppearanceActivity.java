package org.telegram.ui;

import android.view.View;
import android.widget.EditText;

import org.telegram.messenger.TrustedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Appearance: chat list title, centered titles, popup glass. */
public class TGramAppearanceActivity extends UniversalFragment {

    @Override
    protected boolean isTGramPage() {
        return true;
    }

    private static final int ID_TITLE = 1, ID_CENTER = 2, ID_BLUR = 3, ID_IOS = 4, ID_FEEDTAB = 5;
    private static final String[] MODES = {"App name", "Account first name", "Custom text"};

    @Override
    protected boolean useLargeTitle() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return "Appearance";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("iOS Style"));
        items.add(UItem.asButtonCheck(ID_IOS, "iOS Look", "iOS colours, green switches, always-on liquid glass bars. Restart the app to apply colours.").setChecked(TrustedConfig.isIosStyle()));
        items.add(UItem.asButtonCheck(ID_FEEDTAB, "Feed in Bottom Bar", "Adds a Feed button to the floating tab bar. Restart to apply.").setChecked(TrustedConfig.isFeedTab()));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Chat List"));
        items.add(UItem.asButton(ID_TITLE, "Chat List Title", MODES[Math.max(0, Math.min(2, TrustedConfig.getTitleMode()))]));
        items.add(UItem.asButtonCheck(ID_CENTER, "Center Title", "Show titles in the middle of the top bar on every screen").setChecked(TrustedConfig.isTitleCentered()));
        items.add(UItem.asShadow("The chat list title changes as soon as you go back."));
        items.add(UItem.asHeader("Popups"));
        items.add(UItem.asButtonCheck(ID_BLUR, "Glass Blur", "Frosted background behind TrustedGram popups (Android 12+)").setChecked(TrustedConfig.isGlassBlur()));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_FEEDTAB) {
            TrustedConfig.setFeedTab(!TrustedConfig.isFeedTab());
            showRestartRequired();
        } else if (item.id == ID_IOS) {
            TrustedConfig.setIosStyle(!TrustedConfig.isIosStyle());
            showRestartRequired();
        } else if (item.id == ID_BLUR) {
            TrustedConfig.setGlassBlur(!TrustedConfig.isGlassBlur());
            showRestartRequired();
        } else if (item.id == ID_CENTER) {
            TrustedConfig.setTitleCentered(!TrustedConfig.isTitleCentered());
            if (parentLayout != null) TrustedConfig.centerTitles(parentLayout);
        } else if (item.id == ID_TITLE) {
            if (getParentActivity() == null) return;
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle("Title Text");
            b.setItems(MODES, (d, which) -> {
                if (which == 2) {
                    EditText input = new EditText(getParentActivity());
                    input.setSingleLine(true);
                    input.setText(TrustedConfig.getTitleText());
                    input.setSelection(input.length());
                    input.setHint("Enter chat list title");
                    AlertDialog.Builder titleDialog = new AlertDialog.Builder(getParentActivity());
                    titleDialog.setTitle("Custom chat list title");
                    titleDialog.setView(input);
                    titleDialog.setPositiveButton("Save", (dialog, result) -> {
                        String value = input.getText() == null ? "" : input.getText().toString().trim();
                        if (!value.isEmpty()) {
                            TrustedConfig.setTitleText(value);
                            TrustedConfig.setTitleMode(2);
                            listView.adapter.update(true);
                            showRestartRequired();
                        }
                    });
                    titleDialog.setNegativeButton("Cancel", null);
                    showDialog(titleDialog.create());
                } else {
                    TrustedConfig.setTitleMode(which);
                    listView.adapter.update(true);
                    showRestartRequired();
                }
            });
            b.setNegativeButton("Cancel", null);
            showDialog(b.create());
            return;
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
