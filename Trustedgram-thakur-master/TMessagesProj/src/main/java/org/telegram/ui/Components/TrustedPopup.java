package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * TrustedGram premium glass popup: frosted card, glowing TrustedGram logo sitting on the top edge,
 * gradient primary button. Used for update + announcement popups.
 * The blur behind the card uses the real window blur on Android 12+ and a darker glass tint below that.
 */
public class TrustedPopup {

    private final Activity activity;
    private String badge, title, chip, message, notes, primaryText, secondaryText;
    private Runnable primaryAction, secondaryAction;
    private boolean cancelable = true;

    public TrustedPopup(Activity activity) {
        this.activity = activity;
    }

    public TrustedPopup setBadge(String v) { badge = v; return this; }
    public TrustedPopup setTitle(String v) { title = v; return this; }
    public TrustedPopup setChip(String v) { chip = v; return this; }
    public TrustedPopup setMessage(String v) { message = v; return this; }
    public TrustedPopup setNotes(String v) { notes = v; return this; }
    public TrustedPopup setCancelable(boolean v) { cancelable = v; return this; }
    public TrustedPopup setPrimary(String text, Runnable action) { primaryText = text; primaryAction = action; return this; }
    public TrustedPopup setSecondary(String text, Runnable action) { secondaryText = text; secondaryAction = action; return this; }

    private static final int ACCENT_A = 0xFF38A8FF;
    private static final int ACCENT_B = 0xFF6A5CFF;

    /** The TrustedGram mark: dark orb with the white paper-plane arrow, glowing ring. */
    private static class LogoView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private float glow = 0f;
        private ValueAnimator pulse;

        LogoView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            pulse = ValueAnimator.ofFloat(0f, 1f);
            pulse.setDuration(2200);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> { glow = (float) a.getAnimatedValue(); invalidate(); });
            pulse.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (pulse != null) pulse.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f - dp(10);

            // outer glow
            paint.setShader(new RadialGradient(cx, cy, r + dp(10),
                new int[]{0x00000000, Color.argb((int) (70 + 60 * glow), 56, 168, 255), 0x00000000},
                new float[]{0.72f, 0.86f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r + dp(10), paint);

            // orb
            paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFF1B1F2E, 0xFF05060B, Shader.TileMode.CLAMP));
            paint.setShadowLayer(dp(10), 0, dp(4), 0x99000000);
            canvas.drawCircle(cx, cy, r, paint);
            paint.clearShadowLayer();

            // ring
            paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, ACCENT_A, ACCENT_B, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            canvas.drawCircle(cx, cy, r - dp(1), paint);
            paint.setStyle(Paint.Style.FILL);

            // arrow (paper plane), drawn in a 100x100 box centred on the orb
            float s = r * 1.15f / 100f;
            canvas.save();
            canvas.translate(cx - 50 * s, cy - 50 * s);
            arrow.reset();
            arrow.moveTo(80 * s, 22 * s);
            arrow.lineTo(26 * s, 52 * s);
            arrow.lineTo(44 * s, 58 * s);
            arrow.lineTo(50 * s, 78 * s);
            arrow.close();
            paint.setShader(null);
            paint.setColor(0xFFFFFFFF);
            canvas.drawPath(arrow, paint);
            paint.setColor(0xFFCBD5E8);
            Path fold = new Path();
            fold.moveTo(80 * s, 22 * s);
            fold.lineTo(44 * s, 58 * s);
            fold.lineTo(50 * s, 78 * s);
            fold.close();
            canvas.drawPath(fold, paint);
            canvas.restore();
        }
    }

    public Dialog show() {
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean realBlur = Build.VERSION.SDK_INT >= 31;

        FrameLayout root = new FrameLayout(activity);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // ---- glass card
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(58), dp(22), dp(20));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            realBlur ? new int[]{0x66243046, 0x4D10131F, 0x66201B3A} : new int[]{0xF2182236, 0xF20E111C, 0xF21A1636});
        glass.setCornerRadius(dp(28));
        glass.setStroke(dp(1), 0x40FFFFFF);
        card.setBackground(glass);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(18));

        if (badge != null) {
            TextView b = new TextView(activity);
            b.setText(badge);
            b.setTextSize(11);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setLetterSpacing(0.14f);
            b.setTextColor(0xFF7CC6FF);
            b.setGravity(Gravity.CENTER);
            card.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView t = new TextView(activity);
        t.setText(title == null ? "TrustedGram" : title);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        card.addView(t, tlp);

        if (!TextUtils.isEmpty(chip)) {
            TextView c = new TextView(activity);
            c.setText(chip);
            c.setTextSize(12);
            c.setTypeface(Typeface.DEFAULT_BOLD);
            c.setTextColor(0xFFFFFFFF);
            c.setPadding(dp(12), dp(4), dp(12), dp(4));
            GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT_A, ACCENT_B});
            cg.setCornerRadius(dp(20));
            c.setBackground(cg);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(10);
            card.addView(c, clp);
        }

        if (!TextUtils.isEmpty(message)) {
            ScrollView sv = new ScrollView(activity);
            sv.setVerticalScrollBarEnabled(false);
            TextView m = new TextView(activity);
            m.setText(message);
            m.setTextSize(15);
            m.setLineSpacing(dp(2), 1f);
            m.setTextColor(0xFFDCE3F2);
            m.setGravity(Gravity.CENTER_HORIZONTAL);
            m.setTextIsSelectable(false);
            sv.addView(m, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(12);
            card.addView(sv, mlp);
        }

        if (!TextUtils.isEmpty(notes)) {
            TextView nh = new TextView(activity);
            nh.setText("WHAT'S NEW");
            nh.setTextSize(11);
            nh.setTypeface(Typeface.DEFAULT_BOLD);
            nh.setLetterSpacing(0.12f);
            nh.setTextColor(0xFF8FA0BF);
            LinearLayout.LayoutParams nhl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nhl.topMargin = dp(16);
            card.addView(nh, nhl);

            ScrollView ns = new ScrollView(activity);
            ns.setVerticalScrollBarEnabled(false);
            TextView n = new TextView(activity);
            n.setText(notes);
            n.setTextSize(14);
            n.setLineSpacing(dp(2), 1f);
            n.setTextColor(0xFFEAF0FF);
            n.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable ng = new GradientDrawable();
            ng.setColor(0x26FFFFFF);
            ng.setCornerRadius(dp(16));
            ng.setStroke(dp(1), 0x1FFFFFFF);
            ns.setBackground(ng);
            ns.addView(n, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130));
            nl.topMargin = dp(8);
            card.addView(ns, nl);
        }

        // ---- buttons
        TextView primary = new TextView(activity);
        primary.setText(primaryText == null ? "OK" : primaryText);
        primary.setTextSize(16);
        primary.setTypeface(Typeface.DEFAULT_BOLD);
        primary.setTextColor(0xFFFFFFFF);
        primary.setGravity(Gravity.CENTER);
        GradientDrawable pg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT_A, ACCENT_B});
        pg.setCornerRadius(dp(26));
        primary.setBackground(pg);
        primary.setOnClickListener(v -> {
            dismiss(dialog, card, primaryAction);
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        plp.topMargin = dp(20);
        card.addView(primary, plp);

        if (secondaryText != null) {
            TextView sec = new TextView(activity);
            sec.setText(secondaryText);
            sec.setTextSize(15);
            sec.setTypeface(Typeface.DEFAULT_BOLD);
            sec.setTextColor(0xFFB6C3DD);
            sec.setGravity(Gravity.CENTER);
            sec.setOnClickListener(v -> dismiss(dialog, card, secondaryAction));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            slp.topMargin = dp(4);
            card.addView(sec, slp);
        }

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        clp.setMargins(dp(26), dp(64), dp(26), dp(8));
        root.addView(card, clp);

        // ---- logo on the top edge, centred
        LogoView logo = new LogoView(activity);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER_HORIZONTAL);
        // sits half over the card: the card's top margin leaves room for the upper half
        root.addView(logo, llp);

        // keep the logo glued to the top of the card
        card.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) logo.getLayoutParams();
            int wanted = tp - dp(112) / 2;
            if (lp.topMargin != wanted) {
                lp.topMargin = wanted;
                logo.setLayoutParams(lp);
            }
        });

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = realBlur ? 0.35f : 0.65f;
            if (realBlur) {
                try {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    lp.setBlurBehindRadius(dp(24));
                } catch (Throwable ignore) {
                }
            }
            w.setAttributes(lp);
            w.setStatusBarColor(0);
        }

        // entrance: card slides up + fades, logo pops
        card.setAlpha(0f);
        card.setTranslationY(dp(40));
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        logo.setAlpha(0f);
        logo.setScaleX(0.4f);
        logo.setScaleY(0.4f);
        dialog.show();
        card.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(0.8f)).start();
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(420).setInterpolator(new OvershootInterpolator(2.2f)).start();
        return dialog;
    }

    private void dismiss(Dialog dialog, View card, Runnable after) {
        card.animate().alpha(0f).translationY(dp(24)).setDuration(160).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                try {
                    dialog.dismiss();
                } catch (Exception ignore) {
                }
                if (after != null) after.run();
            }
        }).start();
    }
}
