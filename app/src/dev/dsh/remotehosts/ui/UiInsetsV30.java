package dev.dsh.remotehosts.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * API 30+ half of {@link UiInsets}. Kept in a separate class so API 26-29
 * devices never load {@code android.graphics.Insets} / {@code WindowInsets.Type}
 * / {@code WindowInsetsController}.
 */
final class UiInsetsV30 {

    private UiInsetsV30() {
    }

    /** Transparent system bars + edge-to-edge layout (content is inset manually). */
    static void configure(Activity activity) {
        Window window = activity.getWindow();
        window.setDecorFitsSystemWindows(false);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    static WindowInsets dispatch(Activity activity, View appBar, View content, WindowInsets insets,
                                 int barLeft, int barTop, int barRight, int barBottom, int baseBarHeight,
                                 int contentLeft, int contentTop, int contentRight, int contentBottom) {
        Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
        Insets ime = insets.getInsets(WindowInsets.Type.ime());
        int top = bars.top;
        int bottom = Math.max(bars.bottom, ime.bottom);
        UiInsets.applyTopInset(appBar, barLeft, barTop, barRight, barBottom, baseBarHeight, top);
        content.setPadding(contentLeft, contentTop, contentRight, contentBottom + bottom);
        applySystemBarIconAppearance(activity);
        if (ime.bottom > 0) UiInsets.scrollFocusedIntoView(content);
        // Consumed on purpose: the XML fitsSystemWindows padding must not be
        // applied a second time on top of ours.
        return WindowInsets.CONSUMED;
    }

    private static void applySystemBarIconAppearance(Activity activity) {
        if (Build.VERSION.SDK_INT < 30) return;
        WindowInsetsController controller = activity.getWindow().getInsetsController();
        if (controller == null) return;
        // Brand background behind the status bar -> white icons.
        controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        // Light content background behind the navigation bar -> dark icons.
        controller.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }
}
