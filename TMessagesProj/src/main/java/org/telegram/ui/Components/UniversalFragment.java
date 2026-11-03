package org.telegram.ui.Components;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AdjustPanLayoutHelper;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.QuickRepliesController;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

public abstract class UniversalFragment extends BaseFragment {

    public UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY)
                );
            }
        };
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
//                applyScrolledPosition();
                super.onMeasure(widthSpec, heightSpec);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                savedScrollPosition = -1;
            }
        };
        // Settings pages are short, mostly static lists. Avoid the shared 350 ms
        // item-change animator so predictive-back snapshots and edge gestures stay responsive.
        listView.setItemAnimator(null);
        listView.setItemViewCacheSize(8);
        if (isTGramPage()) {
            contentView.setBackground(createGlassBackdrop());
            listView.setBubbleSections();
        }
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (useLargeTitle()) {
            setupLargeTitle(context, contentView);
        }

        return fragmentView = contentView;
    }

    /** True for the TrustedGram settings pages (TGramXxxActivity), which use bubble style glass tiles. */
    protected boolean isTGramPage() {
        return false; // Non-TrustedGram fragments keep Telegram's standard section layout.
    }

    /** Soft gradient behind the glass tiles so the transparency is actually visible. */
    protected android.graphics.drawable.Drawable createGlassBackdrop() {
        final int base = Theme.getColor(Theme.key_windowBackgroundGray);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int top = androidx.core.graphics.ColorUtils.blendARGB(base, accent, 0.22f);
        final int bottom = androidx.core.graphics.ColorUtils.blendARGB(base, accent, 0.05f);
        return new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, base, bottom});
    }

    /** Shows the glass popup telling the user a restart is needed for the setting they just changed. */
    protected void showRestartRequired() {
        if (getParentActivity() == null) return;
        org.telegram.ui.TrustedPopup.show(getParentActivity(), "Restart required", "Restart TrustedGram to apply this change.", "OK", null, null, false);
    }

    /** iOS-style large title that collapses into the action bar while scrolling (TGram pages only). */
    protected boolean useLargeTitle() {
        return false; // normal action-bar title like the rest of the app
    }

    private void setupLargeTitle(Context context, FrameLayout contentView) {
        final int h = AndroidUtilities.dp(56);
        final android.widget.TextView big = new android.widget.TextView(context);
        big.setText(getTitle());
        big.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 32);
        big.setTypeface(AndroidUtilities.bold());
        big.setGravity(android.view.Gravity.CENTER_VERTICAL);
        big.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        big.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        contentView.addView(big, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, android.view.Gravity.TOP));
        listView.setPadding(listView.getPaddingLeft(), h, listView.getPaddingRight(), listView.getPaddingBottom());
        listView.setClipToPadding(false);
        final int titleColor = Theme.getColor(Theme.key_actionBarDefaultTitle);
        actionBar.setTitleColor(androidx.core.graphics.ColorUtils.setAlphaComponent(titleColor, 0));
        listView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                float off;
                View first = rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
                if (first == null) {
                    off = 0;
                } else if (rv.getChildAdapterPosition(first) == 0) {
                    off = Math.max(0, h - first.getTop());
                } else {
                    off = h;
                }
                off = Math.min(off, h);
                big.setTranslationY(-off);
                big.setAlpha(1f - off / h);
                float a = Math.max(0f, Math.min(1f, (off - h * 0.5f) / (h * 0.5f)));
                actionBar.setTitleColor(androidx.core.graphics.ColorUtils.setAlphaComponent(titleColor, (int) (255 * a)));
            }
        });
    }

    protected abstract CharSequence getTitle();
    protected abstract void fillItems(ArrayList<UItem> items, UniversalAdapter adapter);
    protected abstract void onClick(UItem item, View view, int position, float x, float y);
    protected abstract boolean onLongClick(UItem item, View view, int position, float x, float y);

    private int savedScrollPosition = -1;
    private int savedScrollOffset;

    public void saveScrollPosition() {
        if (listView != null && listView.getChildCount() > 0) {
            View view = null;
            int position = -1;
            int top = Integer.MAX_VALUE;
            for (int i = 0; i < listView.getChildCount(); i++) {
                int childPosition = listView.getChildAdapterPosition(listView.getChildAt(i));
                View child = listView.getChildAt(i);
                if (childPosition != RecyclerListView.NO_POSITION && child.getTop() < top) {
                    view = child;
                    position = childPosition;
                    top = child.getTop();
                }
            }
            if (view != null) {
                savedScrollPosition = position;
                savedScrollOffset = view.getTop();
                if (savedScrollPosition == 0 && savedScrollOffset > AndroidUtilities.dp(88)) {
                    savedScrollOffset = AndroidUtilities.dp(88);
                }
                listView.layoutManager.scrollToPositionWithOffset(position, view.getTop() - listView.getPaddingTop());
            }
        }
    }

    public void applyScrolledPosition() {
        if (savedScrollPosition >= 0) {
            listView.layoutManager.scrollToPositionWithOffset(savedScrollPosition, savedScrollOffset - listView.getPaddingTop());
        }
    }
}
