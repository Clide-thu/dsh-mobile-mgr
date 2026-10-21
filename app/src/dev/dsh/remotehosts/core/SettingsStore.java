package dev.dsh.remotehosts.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Process-wide, non-secret user settings (v0.2.5).
 *
 * These two switches used to live elsewhere: {@code 通过外部浏览器连接} replaced the
 * per-host {@code appendToken} flag in v0.2.5, and {@code 自动收起内置页面的顶部横栏}
 * controls the collapsible top bar of {@code WebViewActivity}. Both are plain
 * booleans, so they are stored in a normal {@link SharedPreferences} file — the
 * encrypted {@link HostStore} is reserved for credentials/keys and must not be
 * polluted with preferences.
 *
 * SECURITY: nothing secret is ever written here (no token, password, private key
 * or URL). The file is app-private, and both values are safe to expose in a
 * future settings backup/export if that is ever added.
 *
 * Thread-safety: {@link SharedPreferences} handles the process-wide lock; every
 * accessor reads the current persisted value, so a change takes effect for the
 * next “open” without restarting the app.
 */
public final class SettingsStore {

    /** SharedPreferences file name (not exported; app-private directory). */
    public static final String PREFS_NAME = "settings";

    /** Global "open with the external browser" switch (v0.2.5). */
    public static final String KEY_BROWSER_MODE = "browser_mode";
    /** v0.2.5: enable the collapsible top bar in {@code WebViewActivity}. */
    public static final String KEY_COLLAPSE_APP_BAR = "collapse_app_bar";

    /**
     * Default for {@link #KEY_BROWSER_MODE}: {@code false} = built-in WebView
     * (fully automatic, recommended). A missing key (fresh install or upgrade
     * from v0.2.4) reads as the default; the old per-host flag is not migrated.
     */
    public static final boolean DEFAULT_BROWSER_MODE = false;
    /** Default for {@link #KEY_COLLAPSE_APP_BAR}: enabled. */
    public static final boolean DEFAULT_COLLAPSE_APP_BAR = true;

    private SettingsStore() {
    }

    /** True when "打开" should hand off to the external browser (copy + paste). */
    public static boolean isBrowserMode(Context context) {
        return prefs(context).getBoolean(KEY_BROWSER_MODE, DEFAULT_BROWSER_MODE);
    }

    /** Persist the global browser/WebView choice; takes effect on the next open. */
    public static void setBrowserMode(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BROWSER_MODE, enabled).apply();
    }

    /** True when the built-in WebView top bar may collapse. */
    public static boolean isCollapseAppBar(Context context) {
        return prefs(context).getBoolean(KEY_COLLAPSE_APP_BAR, DEFAULT_COLLAPSE_APP_BAR);
    }

    /** Persist the collapsible-top-bar switch. */
    public static void setCollapseAppBar(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_COLLAPSE_APP_BAR, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
