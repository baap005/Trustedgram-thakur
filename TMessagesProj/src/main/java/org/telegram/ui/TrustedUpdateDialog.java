package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.TrustedConfig;
import org.telegram.messenger.TrustedUpdater;

/**
 * The update screen inside the app: version, what is new, a Download button that downloads the APK here (with progress),
 * then an Install button. Nothing is opened in a browser.
 */
public class TrustedUpdateDialog {

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    public static Dialog show(Activity activity, final TrustedConfig.UpdateInfo info, final boolean blocking) {
        final TextView[] primary = new TextView[1];
        final TextView[] secondary = new TextView[1];
        final TextView[] status = new TextView[1];
        final View[] fill = new View[1];
        final FrameLayout[] track = new FrameLayout[1];
        final TrustedUpdater.Listener[] listener = new TrustedUpdater.Listener[1];

        final Runnable[] render = new Runnable[1];

        Dialog dialog = TrustedPopup.build(activity, blocking ? "Update required" : "Update available", blocking, (act, card, d) -> {
            TextView chip = new TextView(act);
            chip.setText("Version " + (TextUtils.isEmpty(info.name) ? String.valueOf(info.latest) : info.name));
            chip.setTextColor(0xFF8FB4FF);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-1, -2);
            chipLp.topMargin = dp(6);
            card.addView(chip, chipLp);

            String intro = blocking
                ? "This version is no longer supported. Please update to continue."
                : "A new version of TrustedGram is ready.";
            TextView msg = new TextView(act);
            msg.setText(intro);
            msg.setTextColor(0xFFC3C9DA);
            msg.setTextSize(15);
            msg.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
            mlp.topMargin = dp(10);
            card.addView(msg, mlp);

            if (!TextUtils.isEmpty(info.changelog)) {
                ScrollView sv = new ScrollView(act);
                sv.setVerticalScrollBarEnabled(false);
                TextView log = new TextView(act);
                log.setText(info.changelog);
                log.setTextColor(0xFFDDE3F5);
                log.setTextSize(14);
                log.setLineSpacing(dp(2), 1f);
                log.setPadding(dp(14), dp(10), dp(14), dp(10));
                GradientDrawable lg = new GradientDrawable();
                lg.setColor(0x22FFFFFF);
                lg.setCornerRadius(dp(14));
                sv.setBackground(lg);
                sv.addView(log);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(120));
                slp.topMargin = dp(12);
                card.addView(sv, slp);
            }

            // progress
            track[0] = new FrameLayout(act);
            GradientDrawable tg = new GradientDrawable();
            tg.setColor(0x33FFFFFF);
            tg.setCornerRadius(dp(4));
            track[0].setBackground(tg);
            fill[0] = new View(act);
            GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2F6BFF, 0xFF6C8DFF});
            fg.setCornerRadius(dp(4));
            fill[0].setBackground(fg);
            track[0].addView(fill[0], new FrameLayout.LayoutParams(0, -1));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(8));
            tlp.topMargin = dp(16);
            card.addView(track[0], tlp);
            track[0].setVisibility(View.GONE);

            status[0] = new TextView(act);
            status[0].setTextColor(0xFFC3C9DA);
            status[0].setTextSize(14);
            status[0].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, -2);
            stlp.topMargin = dp(10);
            card.addView(status[0], stlp);

            primary[0] = TrustedPopup.primaryButton(act, "Download", v -> {
                int st = TrustedUpdater.getState();
                if (st == TrustedUpdater.READY) {
                    TrustedUpdater.install(act);
                } else if (st == TrustedUpdater.DOWNLOADING) {
                    // already running
                } else {
                    TrustedUpdater.start(info.url, info.size, info.sha256, info.latest);
                }
            });
            card.addView(primary[0], TrustedPopup.buttonParams(18));

            secondary[0] = TrustedPopup.secondaryButton(act, "Later", v -> {
                if (TrustedUpdater.getState() == TrustedUpdater.DOWNLOADING) TrustedUpdater.cancel();
                else d.dismiss();
            });
            card.addView(secondary[0], TrustedPopup.buttonParams2());
            if (blocking) secondary[0].setVisibility(View.GONE);

            render[0] = () -> {
                int st = TrustedUpdater.getState();
                if (st == TrustedUpdater.IDLE && TrustedUpdater.isDownloaded(info.latest)) st = TrustedUpdater.READY;
                track[0].setVisibility(st == TrustedUpdater.DOWNLOADING ? View.VISIBLE : View.GONE);
                status[0].setTextColor(0xFFC3C9DA);
                if (st == TrustedUpdater.DOWNLOADING) {
                    int w = track[0].getWidth();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill[0].getLayoutParams();
                    lp.width = w * TrustedUpdater.getPercent() / 100;
                    fill[0].setLayoutParams(lp);
                    String sizes = TrustedUpdater.getTotalBytes() > 0 ? "  (" + mb(TrustedUpdater.getDoneBytes()) + " / " + mb(TrustedUpdater.getTotalBytes()) + ")" : "";
                    status[0].setText("Downloading... " + TrustedUpdater.getPercent() + "%" + sizes);
                    primary[0].setText("Downloading...");
                    primary[0].setAlpha(0.6f);
                    secondary[0].setText("Cancel");
                    secondary[0].setVisibility(View.VISIBLE);
                } else if (st == TrustedUpdater.READY) {
                    status[0].setText("Download finished. Tap Install, then Android will ask you to confirm.\nTrustedGram opens again after the update.");
                    primary[0].setText("Install update");
                    primary[0].setAlpha(1f);
                    secondary[0].setText("Later");
                    secondary[0].setVisibility(blocking ? View.GONE : View.VISIBLE);
                } else if (st == TrustedUpdater.ERROR) {
                    status[0].setTextColor(0xFFFF8A8A);
                    status[0].setText(TrustedUpdater.getError());
                    primary[0].setText("Try again");
                    primary[0].setAlpha(1f);
                    secondary[0].setText("Later");
                    secondary[0].setVisibility(blocking ? View.GONE : View.VISIBLE);
                } else {
                    status[0].setText(info.size > 0 ? "Download size: " + mb(info.size) : "");
                    status[0].setVisibility(info.size > 0 ? View.VISIBLE : View.GONE);
                    primary[0].setText("Download");
                    primary[0].setAlpha(1f);
                    secondary[0].setText("Later");
                    secondary[0].setVisibility(blocking ? View.GONE : View.VISIBLE);
                }
                if (st != TrustedUpdater.IDLE) status[0].setVisibility(View.VISIBLE);
            };
            listener[0] = () -> render[0].run();
            TrustedUpdater.addListener(listener[0]);
            TrustedPopup.onDismiss(d, () -> TrustedUpdater.removeListener(listener[0]));
            render[0].run();
        });
        return dialog;
    }
}
