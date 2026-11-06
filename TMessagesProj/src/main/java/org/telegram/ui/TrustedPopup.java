package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.TrustedConfig;

/** Glass-style TrustedGram popup with the logo at the top centre. Used for announcements and updates. */
public class TrustedPopup {

    /** Fills the glass card (below the title) and returns nothing; the dialog is passed so buttons can dismiss it. */
    private static final java.util.Map<Dialog, Runnable> dismissHooks = new java.util.WeakHashMap<>();

    /** Lets the content of a popup run something when the popup closes. */
    public static void onDismiss(Dialog dialog, Runnable r) {
        dismissHooks.put(dialog, r);
    }

    public interface Content {
        void build(Activity activity, LinearLayout card, Dialog dialog);
    }

    public static Dialog show(Activity activity, String title, String message, String primary, Runnable onPrimary, String secondary, boolean blocking) {
        return build(activity, title, blocking, (act, card, dialog) -> {
            if (!TextUtils.isEmpty(message)) {
                TextView msgView = new TextView(act);
                msgView.setText(message);
                msgView.setTextColor(0xFFC3C9DA);
                msgView.setTextSize(15);
                msgView.setLineSpacing(dp(2), 1f);
                msgView.setGravity(Gravity.CENTER);
                msgView.setMaxLines(12);
                msgView.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(10);
                card.addView(msgView, lp);
            }
            card.addView(primaryButton(act, primary, v -> {
                try {
                    if (onPrimary != null) onPrimary.run();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (!blocking) dialog.dismiss();
            }), buttonParams(22));
            if (secondary != null) {
                card.addView(secondaryButton(act, secondary, v -> dialog.dismiss()), buttonParams2());
            }
        });
    }

    public static TextView primaryButton(Activity activity, String text, View.OnClickListener click) {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable pbg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2F6BFF, 0xFF6C8DFF});
        pbg.setCornerRadius(dp(24));
        b.setBackground(pbg);
        b.setOnClickListener(click);
        return b;
    }

    public static TextView secondaryButton(Activity activity, String text, View.OnClickListener click) {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextColor(0xFF9AA3BC);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(click);
        return b;
    }

    public static LinearLayout.LayoutParams buttonParams(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(topDp);
        return lp;
    }

    public static LinearLayout.LayoutParams buttonParams2() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.topMargin = dp(4);
        return lp;
    }

    /** The glass shell: card, logo on top, blur, animations. The content callback adds everything under the title. */
    public static Dialog build(Activity activity, String title, boolean blocking, Content content) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(activity);
        FrameLayout holder = new FrameLayout(activity);
        int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(48), dp(360));
        root.addView(holder, new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(56), dp(24), dp(20));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC2A3048, 0xCC10121A});
        bg.setCornerRadius(dp(30));
        bg.setStroke(dp(1), 0x40FFFFFF);
        card.setBackground(bg);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(42);
        holder.addView(card, cardLp);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(21);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        card.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        content.build(activity, card, dialog);

        // logo, top centre, overlapping the card
        FrameLayout logoCircle = new FrameLayout(activity);
        GradientDrawable lbg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF222838, 0xFF000000});
        lbg.setShape(GradientDrawable.OVAL);
        lbg.setStroke(dp(2), 0x99FFFFFF);
        logoCircle.setBackground(lbg);
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.drawable.trusted_splash);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(6), dp(6), dp(6), dp(6));
        logoCircle.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        holder.addView(logoCircle, llp);

        dialog.setContentView(root);
        dialog.setCancelable(!blocking);
        dialog.setCanceledOnTouchOutside(!blocking);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
            if (Build.VERSION.SDK_INT >= 31 && TrustedConfig.isGlassBlur()) {
                try {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    w.getAttributes().setBlurBehindRadius(40);
                    w.setBackgroundBlurRadius(60);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        }

        dialog.show();

        // entrance animation
        holder.setAlpha(0f);
        holder.setScaleX(0.85f);
        holder.setScaleY(0.85f);
        holder.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(1.1f)).start();
        logoCircle.setScaleX(0f);
        logoCircle.setScaleY(0f);
        logoCircle.animate().scaleX(1f).scaleY(1f).setStartDelay(140).setDuration(380).setInterpolator(new OvershootInterpolator(2f)).start();
        ObjectAnimator pulse = ObjectAnimator.ofFloat(logoCircle, View.ROTATION, -6f, 6f);
        pulse.setDuration(2400);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setStartDelay(600);
        pulse.start();
        dialog.setOnDismissListener(d -> {
            pulse.cancel();
            Runnable r = dismissHooks.remove(dialog);
            if (r != null) r.run();
        });
        return dialog;
    }
}
