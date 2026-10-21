package dev.dsh.remotehosts.ui;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import dev.dsh.remotehosts.R;

/**
 * Runtime system-inset fallback for the in-layout top bar (docs task-12).
 *
 * Android 15 (targetSdk 35) forces edge-to-edge, and some ROMs do it even for
 * older targetSdk values, in which case {@code android:fitsSystemWindows} is not
 * reliable. Each activity's root view therefore installs an explicit
 * {@link View.OnApplyWindowInsetsListener}:
 *
 *   - status-bar top inset -> top bar paddingTop + layout height (brand colour
 *     extends behind the status bar, so light status icons are requested);
 *   - navigation-bar / IME bottom inset -> content area paddingBottom (the
 *     ScrollView/ListView use clipToPadding=false so the last item can scroll
 *     clear of the navigation bar).
 *
 * The listener runs instead of (not in addition to) the XML fitsSystemWindows
 * padding: it consumes the insets so nothing is applied twice.
 *
 * API 30+ specifics live in {@link UiInsetsV30} so this class never loads the
 * newer framework types on API 26-29 devices.
 */
final class UiInsets {

    private UiInsets() {
    }

    static void apply(Activity activity) {
        final View root = activity.findViewById(R.id.root_view);
        final View appBar = activity.findViewById(R.id.app_bar);
        final View content = activity.findViewById(R.id.content_area);
        if (root == null || appBar == null || content == null) return;

        final int barLeft = appBar.getPaddingLeft();
        final int barTop = appBar.getPaddingTop();
        final int barRight = appBar.getPaddingRight();
        final int barBottom = appBar.getPaddingBottom();
        final int contentLeft = content.getPaddingLeft();
        final int contentTop = content.getPaddingTop();
        final int contentRight = content.getPaddingRight();
        final int contentBottom = content.getPaddingBottom();
        final int baseBarHeight = appBar.getLayoutParams() == null
                ? -1 : appBar.getLayoutParams().height;

        // Deterministic edge-to-edge from API 30 on, so the inset callback always
        // receives the real bars instead of ROM-dependent consumed insets.
        if (Build.VERSION.SDK_INT >= 30) {
            UiInsetsV30.configure(activity);
        }

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    return UiInsetsV30.dispatch(activity, appBar, content, insets,
                            barLeft, barTop, barRight, barBottom, baseBarHeight,
                            contentLeft, contentTop, contentRight, contentBottom);
                }
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                if (top > 0) {
                    // Defensive path for ROMs that force edge-to-edge on API 26-29.
                    applyTopInset(appBar, barLeft, barTop, barRight, barBottom, baseBarHeight, top);
                    content.setPadding(contentLeft, contentTop, contentRight, contentBottom + bottom);
                }
                return consumeLegacy(insets);
            }
        });
        root.requestApplyInsets();
    }

    /**
     * Keep the top bar's height and padding in sync with the status-bar inset.
     *
     * v0.2.5: the base (inset-excluded) height is read from the bar's plain tag
     * when {@link #setBarBaseHeight} has installed one, so a bar that changes
     * height at runtime (the collapsible WebView top bar) keeps working across
     * inset changes. Every other activity never sets a tag and therefore keeps
     * using the height captured by {@link #apply}.
     */
    static void applyTopInset(View appBar, int left, int top, int right, int bottom,
                              int baseHeight, int insetTop) {
        Object baseHeightTag = appBar.getTag();
        if (baseHeightTag instanceof Integer) {
            baseHeight = ((Integer) baseHeightTag).intValue();
        }
        if (baseHeight >= 0) {
            android.view.ViewGroup.LayoutParams params = appBar.getLayoutParams();
            int targetHeight = baseHeight + insetTop;
            if (params != null && params.height != targetHeight) {
                params.height = targetHeight;
                appBar.setLayoutParams(params);
            }
        }
        int targetPaddingTop = top + insetTop;
        if (appBar.getPaddingTop() != targetPaddingTop) {
            appBar.setPadding(left, targetPaddingTop, right, bottom);
        }
    }

    /**
     * v0.2.5: override the top bar's base height (excluding the status-bar inset)
     * for bars whose height changes at runtime. The value is stored as the view's
     * plain tag; {@link #applyTopInset} prefers it over the height captured at
     * {@link #apply} time. Callers must request a new inset dispatch afterwards
     * ({@code root_view.requestApplyInsets()}) so the inset keeps being added on
     * top of the new base height.
     */
    static void setBarBaseHeight(View bar, int heightPx) {
        if (bar == null) return;
        bar.setTag(Integer.valueOf(Math.max(0, heightPx)));
    }

    /**
     * Edge-to-edge + a shrinking content area means the platform's adjustResize
     * cannot reveal a focused text field by itself; scroll it into view once the
     * IME-sized padding has been applied.
     */
    static void scrollFocusedIntoView(final View content) {
        content.post(new Runnable() {
            @Override
            public void run() {
                View focused = content.findFocus();
                if (focused == null || focused.getHeight() <= 0) return;
                focused.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, focused.getWidth(), focused.getHeight()), true);
            }
        });
    }

    private static WindowInsets consumeLegacy(WindowInsets insets) {
        try {
            return insets.consumeSystemWindowInsets();
        } catch (RuntimeException ignored) {
            return insets;
        }
    }
}
