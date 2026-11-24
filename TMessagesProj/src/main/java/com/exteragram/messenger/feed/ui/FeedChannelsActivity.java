/*
 * TrustedGram adaptation of exteraGram feed channel management.
 * Copyright © exteraGram, 2022-2026. GNU GPL v3 or later; see ADDITIONAL_TERMS.md.
 */
package com.exteragram.messenger.feed.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import com.exteragram.messenger.feed.FeedConfig;
import com.exteragram.messenger.feed.FeedController;

import java.util.ArrayList;
import java.util.HashSet;

public class FeedChannelsActivity extends BaseFragment {

    private LinearLayout rows;
    private FeedConfig config;
    private FeedController controller;

    @Override
    public View createView(Context context) {
        config = FeedConfig.getInstance(currentAccount);
        controller = FeedController.getInstance(currentAccount);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        TextView title = new TextView(context);
        title.setText("Feed channels");
        title.setTextSize(23);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));

        TextView hint = new TextView(context);
        hint.setText("Choose which subscribed channels appear in your Feed.");
        hint.setTextSize(14);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        root.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        TextView archived = new TextView(context);
        archived.setText("Include archived channels: " + (config.isIncludeArchived() ? "On" : "Off"));
        archived.setTextSize(14);
        archived.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        archived.setPadding(dp(12), dp(12), dp(12), dp(12));
        archived.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundWhite)));
        root.addView(archived, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 10));
        archived.setOnClickListener(v -> {
            boolean enabled = !config.isIncludeArchived();
            config.setIncludeArchived(enabled);
            archived.setText("Include archived channels: " + (enabled ? "On" : "Off"));
            controller.applyConfigChange(null);
            loadChannels();
        });

        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        fragmentView = root;
        loadChannels();
        return root;
    }

    private void loadChannels() {
        if (controller == null || rows == null) return;
        rows.removeAllViews();
        TextView loading = new TextView(getContext());
        loading.setText("Loading channels…");
        loading.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        loading.setPadding(dp(8), dp(20), dp(8), dp(20));
        rows.addView(loading);
        controller.loadChannels(true, (channels, includedCount, failed, snapshot) -> {
            if (rows == null) return;
            rows.removeAllViews();
            if (failed) {
                addInfo("Could not load channels. Please try again.");
                return;
            }
            if (channels == null || channels.isEmpty()) {
                addInfo("Join channels to start building your Feed.");
                return;
            }
            for (TLRPC.Chat chat : channels) {
                addChannelRow(chat);
            }
        });
    }

    private void addInfo(String value) {
        TextView info = new TextView(getContext());
        info.setText(value);
        info.setTextSize(14);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        info.setPadding(dp(8), dp(18), dp(8), dp(18));
        rows.addView(info);
    }

    private void addChannelRow(TLRPC.Chat chat) {
        long dialogId = -chat.id;
        int selectedCategoryId = config.getSelectedCategoryId();
        FeedConfig.Category selectedCategory = selectedCategoryId > 0 ? config.getCategory(selectedCategoryId) : null;
        boolean included = selectedCategory != null ? selectedCategory.getChannels().contains(dialogId) : !config.isExcluded(dialogId);
        TextView row = new TextView(getContext());
        row.setText((included ? "✓  " : "○  ") + chat.title);
        row.setTextSize(16);
        row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundWhite)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        rows.addView(row, lp);
        row.setOnClickListener(v -> {
            boolean nextIncluded;
            if (selectedCategory != null) {
                HashSet<Long> channels = new HashSet<>(selectedCategory.getChannels());
                nextIncluded = !channels.contains(dialogId);
                if (nextIncluded) channels.add(dialogId); else channels.remove(dialogId);
                config.saveCategory(selectedCategory.id, selectedCategory.title, selectedCategory.emojiId, channels);
            } else {
                nextIncluded = config.isExcluded(dialogId);
                config.setExcluded(dialogId, !nextIncluded);
            }
            row.setText((nextIncluded ? "✓  " : "○  ") + chat.title);
            controller.applyConfigChange(null);
        });
    }
}
