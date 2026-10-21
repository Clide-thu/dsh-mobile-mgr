package dev.dsh.remotehosts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.CredentialUrl;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.HostStore;
import dev.dsh.remotehosts.core.SettingsStore;

/**
 * Built-in, fully automatic DSH Web UI viewer (docs/app-spec.md §7.5, v0.2.4; v0.2.5
 * adds the collapsible top bar).
 *
 * Started by {@link MainActivity} when the GLOBAL
 * {@link SettingsStore#isBrowserMode} setting is off (default). The Intent only
 * carries {@code alias} + {@code localPort}; the token is read from the encrypted
 * {@link HostStore} inside this activity and is used exactly once, in the
 * background token-exchange request {@link CredentialUrl#browserUrlFor(int, String)}.
 * The URL finally loaded by the WebView is {@link CredentialUrl#plainUrlFor(int)}
 * and never contains the token.
 *
 * Why an in-app WebView (and not an external browser): Android browsers treat
 * an "external app + 303 redirect" navigation as cross-site and drop the remote
 * {@code SameSite=Strict} login cookie. By performing the token exchange itself
 * and injecting the resulting {@code dsh-auth-*} cookie into its own
 * {@link CookieManager}, the app can load the clean URL in a first-party
 * context.
 *
 * SECURITY: no logging of the token/cookie, no token in an Intent extra, no
 * file/content access in the WebView, mixed content blocked, external http(s)
 * links handed to the system browser.
 *
 * v0.2.5: the top bar is collapsible to maximize the WebView viewport when the
 * global setting {@link SettingsStore#isCollapseAppBar} is on. Expanded it is
 * 56dp (back arrow, title, subtitle, collapse action); collapsed it is 32dp and
 * shows only one small host-name line, and tapping that bar expands it again.
 * The bar auto-collapses when the page scrolls down and auto-expands when it is
 * scrolled back to the top; see docs/handoff-v025.md and docs/app-spec.md §7.5.
 */
public class WebViewActivity extends Activity {

    /** Extra: alias of the host to display (never the token). */
    public static final String EXTRA_ALIAS = "dev.dsh.remotehosts.extra.WEBVIEW_ALIAS";
    /** Extra: local loopback port of the already-open SSH tunnel. */
    public static final String EXTRA_LOCAL_PORT = "dev.dsh.remotehosts.extra.WEBVIEW_LOCAL_PORT";

    /** Timeout for the token-exchange request (docs/app-spec.md §7.5). */
    private static final int EXCHANGE_TIMEOUT_MS = 5000;
    /** Cookie name prefix set by the remote dsh web on a successful token exchange. */
    private static final String AUTH_COOKIE_PREFIX = "dsh-auth-";

    // v0.2.5 collapsible top bar. Heights exclude the status-bar inset, which
    // UiInsets adds on top of the current base height.
    private static final int APP_BAR_EXPANDED_DP = 56;
    private static final int APP_BAR_COLLAPSED_DP = 32;
    /** Scroll past this distance (and moving down) auto-collapses the bar. */
    private static final int AUTO_COLLAPSE_SCROLL_DP = 12;
    /** Scrolling back up into this distance from the top auto-expands the bar. */
    private static final int AUTO_EXPAND_SCROLL_DP = 4;
    /** Title text size when the bar is expanded / collapsed (sp). */
    private static final float TITLE_SIZE_EXPANDED_SP = 18f;
    private static final float TITLE_SIZE_COLLAPSED_SP = 13f;

    private String alias;
    private int localPort;
    private boolean destroyed;

    private WebView webView;
    private ProgressBar webProgress;
    private View errorPanel;
    private TextView errorMessage;
    private Button retryButton;
    private Button editButton;
    private View appBar;
    private TextView appBarTitle;
    private View appBarBack;
    /** True when the global setting allows the top bar to collapse. */
    private boolean collapseEnabled;
    /** Current top-bar state; always false while collapse is disabled. */
    private boolean barCollapsed;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Incremented for every attempt; a stale background result must be ignored. */
    private int authAttempt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        alias = getIntent().getStringExtra(EXTRA_ALIAS);
        localPort = getIntent().getIntExtra(EXTRA_LOCAL_PORT, 0);

        AppBar.bind(this);
        AppBar.setTitle(this, R.string.webview_title);
        AppBar.setBackVisible(this, true);
        AppBar.setSubtitle(this, alias);
        UiInsets.apply(this);

        bindViews();
        setupCollapsibleAppBar();
        // docs/app-spec.md §7.5: the back arrow follows the same rule as the
        // hardware back key (WebView history first, then finish).
        findViewById(R.id.appbar_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                navigateBack();
            }
        });

        configureWebView();
        startAuthFlow();
    }

    private void bindViews() {
        webView = (WebView) findViewById(R.id.web_view);
        webProgress = (ProgressBar) findViewById(R.id.web_progress);
        errorPanel = findViewById(R.id.web_error_panel);
        errorMessage = (TextView) findViewById(R.id.web_error_message);
        retryButton = (Button) findViewById(R.id.btn_web_retry);
        editButton = (Button) findViewById(R.id.btn_web_edit_credential);
        findViewById(R.id.btn_web_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                finish();
            }
        });
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                startAuthFlow();
            }
        });
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                openCredentialEditor();
            }
        });
    }

    // ------------------------------------------------ collapsible top bar (v0.2.5)

    /**
     * v0.2.5: wire the collapsible top bar. When the global setting is off the
     * bar keeps the v0.2.4 behaviour: always 56dp, no collapse action and no
     * auto-collapse. The status-bar inset is handled by {@link UiInsets} on top
     * of whichever base height is currently active.
     */
    private void setupCollapsibleAppBar() {
        appBar = findViewById(R.id.app_bar);
        appBarTitle = (TextView) findViewById(R.id.appbar_title);
        appBarBack = findViewById(R.id.appbar_back);
        collapseEnabled = SettingsStore.isCollapseAppBar(this);
        if (!collapseEnabled || appBar == null || appBarTitle == null) {
            // Defensive: never leave a stray action when the bar cannot collapse.
            collapseEnabled = false;
            AppBar.setCollapseVisible(this, false);
            return;
        }
        AppBar.setCollapseVisible(this, true);
        View collapse = findViewById(R.id.appbar_collapse);
        if (collapse != null) {
            collapse.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View ignored) {
                    setBarCollapsed(true);
                }
            });
        }
        installAutoCollapse();
    }

    /**
     * v0.2.5 auto collapse: scrolling down past
     * {@link #AUTO_COLLAPSE_SCROLL_DP} collapses the bar; scrolling back up into
     * the top {@link #AUTO_EXPAND_SCROLL_DP} expands it. Scrolling up in the
     * middle of a page never expands the bar, so it cannot fight a manual
     * collapse.
     */
    private void installAutoCollapse() {
        if (webView == null) return;
        final int collapseThreshold = dpToPx(AUTO_COLLAPSE_SCROLL_DP);
        final int expandThreshold = dpToPx(AUTO_EXPAND_SCROLL_DP);
        webView.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View view, int scrollX, int scrollY,
                                       int oldScrollX, int oldScrollY) {
                if (destroyed || !collapseEnabled) return;
                if (barCollapsed) {
                    // Only an upward move that reaches the top zone expands: a
                    // downward scroll at the top must not undo a manual collapse.
                    boolean nearTop = scrollY >= 0 && scrollY <= expandThreshold;
                    if (nearTop && scrollY < oldScrollY) setBarCollapsed(false);
                    return;
                }
                if (scrollY > collapseThreshold && scrollY > oldScrollY) {
                    setBarCollapsed(true);
                }
            }
        });
    }

    /**
     * Switch the top bar between expanded (56dp: back arrow, 18sp bold title,
     * subtitle, collapse action) and collapsed (32dp: one 13sp host-name line,
     * whole bar tappable to expand). No-op while the setting is off.
     */
    private void setBarCollapsed(boolean collapsed) {
        if (!collapseEnabled || destroyed || appBar == null || barCollapsed == collapsed) return;
        barCollapsed = collapsed;

        if (collapsed) {
            AppBar.setTitle(this, collapsedBarTitle());
            appBarTitle.setTextSize(TITLE_SIZE_COLLAPSED_SP);
            appBarTitle.setTypeface(Typeface.DEFAULT);
        } else {
            AppBar.setTitle(this, getString(R.string.webview_title));
            appBarTitle.setTextSize(TITLE_SIZE_EXPANDED_SP);
            appBarTitle.setTypeface(Typeface.DEFAULT_BOLD);
        }
        if (appBarBack != null) {
            appBarBack.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        AppBar.setSubtitle(this, collapsed ? null : alias);
        AppBar.setCollapseVisible(this, !collapsed);
        // The collapsed bar itself is the "expand" affordance; the expanded bar
        // keeps its child actions (back / collapse) and has no bar-level action.
        if (collapsed) {
            appBar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View ignored) {
                    setBarCollapsed(false);
                }
            });
            appBar.setClickable(true);
        } else {
            appBar.setOnClickListener(null);
            appBar.setClickable(false);
        }
        applyBarBaseHeight(collapsed ? APP_BAR_COLLAPSED_DP : APP_BAR_EXPANDED_DP);
    }

    /**
     * Apply a new top-bar base height and keep the {@link UiInsets} contract: the
     * status-bar inset currently in paddingTop is re-added immediately (no
     * one-frame jump) and the plain tag tells the inset listener which base
     * height to use from now on.
     */
    private void applyBarBaseHeight(int baseDp) {
        int basePx = dpToPx(baseDp);
        int insetTop = appBar.getPaddingTop();
        ViewGroup.LayoutParams params = appBar.getLayoutParams();
        if (params != null) {
            params.height = basePx + insetTop;
            appBar.setLayoutParams(params);
        }
        UiInsets.setBarBaseHeight(appBar, basePx);
        View root = findViewById(R.id.root_view);
        if (root != null) root.requestApplyInsets();
    }

    /** One-line host name shown on the collapsed bar; never empty. */
    private CharSequence collapsedBarTitle() {
        if (alias != null && !alias.trim().isEmpty()) return alias;
        HostConfig host = HostStore.get(this).find(alias);
        if (host != null) {
            String endpoint = host.sshEndpoint();
            if (endpoint != null && !endpoint.trim().isEmpty()) return endpoint;
        }
        if (localPort > 0) return "127.0.0.1:" + localPort;
        return getString(R.string.webview_title);
    }

    // ------------------------------------------------------------ web config

    /**
     * Security configuration from docs/app-spec.md §7.5. Note: no
     * {@code setWebContentsDebuggingEnabled()} call (debugging stays off).
     */
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Links with target=_blank load in this same window (no popup windows);
        // they still pass through shouldOverrideUrlLoading for the host check.
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(getColor(R.color.background));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                Uri uri = request.getUrl();
                if (isTunnelUri(uri)) {
                    // Same tunnel: allow any path/query inside the WebView.
                    return false;
                }
                // Every http(s) link to another origin goes to the system browser
                // (docs/app-spec.md §7.5). The token never appears in these URLs.
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (destroyed) return;
                hideError();
                webProgress.setIndeterminate(false);
                webProgress.setProgress(0);
                webProgress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (destroyed) return;
                webProgress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (destroyed || request == null || !request.isForMainFrame()) return;
                String description = error == null || error.getDescription() == null
                        ? "" : error.getDescription().toString();
                showError(getString(R.string.webview_error_page, description), true, false);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (destroyed || request == null || !request.isForMainFrame() || errorResponse == null) {
                    return;
                }
                int status = errorResponse.getStatusCode();
                if (status == 401 || status == 403) {
                    // The injected cookie was not accepted; this mirrors the
                    // token-exchange rejection path with an edit shortcut.
                    showError(getString(R.string.webview_error_rejected_token), true, true);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (destroyed) return;
                if (newProgress >= 100) {
                    webProgress.setVisibility(View.GONE);
                } else if (webProgress.getVisibility() == View.VISIBLE) {
                    webProgress.setProgress(newProgress);
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                if (destroyed || isFinishing()) {
                    result.cancel();
                    return true;
                }
                new AlertDialog.Builder(WebViewActivity.this)
                        .setTitle(R.string.js_alert_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                if (destroyed || isFinishing()) {
                    result.cancel();
                    return true;
                }
                new AlertDialog.Builder(WebViewActivity.this)
                        .setTitle(R.string.js_confirm_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      final JsPromptResult result) {
                if (destroyed || isFinishing()) {
                    result.cancel();
                    return true;
                }
                final EditText input = new EditText(WebViewActivity.this);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                input.setHint(R.string.js_prompt_hint);
                if (defaultValue != null) {
                    input.setText(defaultValue);
                    input.setSelection(input.getText().length());
                }
                new AlertDialog.Builder(WebViewActivity.this)
                        .setTitle(R.string.js_prompt_title)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm(input.getText().toString());
                            }
                        })
                        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                // Do not let page console output reach logcat: it could contain
                // diagnostic data from the remote UI (defence in depth for §7.5.6).
                return true;
            }
        });
    }

    /** True when {@code uri} points at this activity's own tunnel endpoint. */
    private boolean isTunnelUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        String host = uri.getHost();
        if (host == null) return false;
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host);
        return loopback && uri.getPort() == localPort;
    }

    // ------------------------------------------------------- token exchange

    /**
     * Full automatic login flow (docs/app-spec.md §7.5 steps 1-3), all network
     * work on a background thread; only the UI thread touches views.
     */
    private void startAuthFlow() {
        if (destroyed || isFinishing()) return;
        HostConfig host = HostStore.get(this).find(alias);
        if (host == null) {
            showError(getString(R.string.webview_error_unknown_host), false, false);
            return;
        }
        String token = host.token == null ? "" : host.token;
        if (token.isEmpty()) {
            showError(getString(R.string.webview_error_no_token), false, true);
            return;
        }
        if (localPort < 1 || localPort > 65535) {
            showError(getString(R.string.webview_error_bad_port), false, false);
            return;
        }
        final String authUrl;
        try {
            // The ONLY place this activity puts the token into a URL.
            authUrl = CredentialUrl.browserUrlFor(localPort, token);
        } catch (IllegalArgumentException error) {
            showError(getString(R.string.webview_error_bad_port), false, false);
            return;
        }

        final int attempt = ++authAttempt;
        if (webView != null) webView.stopLoading();
        showLoading();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AuthResult result = exchangeToken(authUrl);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Ignore stale results (destroyed activity or a retry
                        // that already superseded this attempt).
                        if (destroyed || isFinishing() || attempt != authAttempt) return;
                        onAuthResult(result);
                    }
                });
            }
        }, "dsh-webview-auth").start();
    }

    /** Background part: GET the token URL without following the 303 redirect. */
    private static AuthResult exchangeToken(String authUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(authUrl).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(EXCHANGE_TIMEOUT_MS);
            connection.setReadTimeout(EXCHANGE_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                return AuthResult.httpRejected(status);
            }
            if (status < 200 || status >= 400) {
                return AuthResult.httpOther(status);
            }
            String cookiePair = findAuthCookie(connection.getHeaderFields());
            if (cookiePair == null) {
                return AuthResult.noCookie();
            }
            return AuthResult.success(cookiePair);
        } catch (IOException error) {
            // Do not propagate error.getMessage(): it must never carry the URL.
            return AuthResult.unreachable();
        } catch (RuntimeException error) {
            return AuthResult.unreachable();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Find the first {@code Set-Cookie} header whose cookie name starts with
     * {@code dsh-auth-} and return its {@code name=value} pair (attributes are
     * rewritten by the caller). Header names are matched case-insensitively.
     */
    private static String findAuthCookie(Map<String, List<String>> headerFields) {
        if (headerFields == null) return null;
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            String name = entry.getKey();
            if (name == null || !"Set-Cookie".equalsIgnoreCase(name)) continue;
            List<String> values = entry.getValue();
            if (values == null) continue;
            for (String header : values) {
                if (header == null) continue;
                String pair = header.split(";", 2)[0].trim();
                int equals = pair.indexOf('=');
                if (equals <= 0) continue;
                String cookieName = pair.substring(0, equals).trim();
                if (cookieName.startsWith(AUTH_COOKIE_PREFIX)) return pair;
            }
        }
        return null;
    }

    private void onAuthResult(AuthResult result) {
        if (destroyed || isFinishing()) return;
        if (result.cookiePair != null) {
            injectCookie(result.cookiePair);
            webProgress.setIndeterminate(false);
            webProgress.setProgress(0);
            webProgress.setVisibility(View.VISIBLE);
            // v0.2.4: the loaded URL has no token (docs/app-spec.md §7.5.3).
            webView.loadUrl(CredentialUrl.plainUrlFor(localPort));
            return;
        }
        webProgress.setVisibility(View.GONE);
        switch (result.failure) {
            case HTTP_REJECTED:
                showError(getString(R.string.webview_error_rejected_token), true, true);
                break;
            case NO_COOKIE:
                showError(getString(R.string.webview_error_no_cookie), true, true);
                break;
            case HTTP_OTHER:
                showError(getString(R.string.webview_error_http, result.status), true, true);
                break;
            case UNREACHABLE:
            default:
                showError(getString(R.string.webview_error_unreachable), true, false);
                break;
        }
    }

    /**
     * Inject the login cookie exactly as docs/app-spec.md §7.5.2 describes:
     * {@code name=value; Path=/; SameSite=Lax; HttpOnly} for
     * {@code http://127.0.0.1:<localPort>/}.
     */
    private void injectCookie(String cookiePair) {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        cookieManager.setCookie("http://127.0.0.1:" + localPort + "/",
                cookiePair + "; Path=/; SameSite=Lax; HttpOnly");
        cookieManager.flush();
    }

    // ---------------------------------------------------------------- errors

    private void showLoading() {
        hideError();
        webProgress.setIndeterminate(true);
        webProgress.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        if (errorPanel != null) errorPanel.setVisibility(View.GONE);
    }

    private void showError(String message, boolean canRetry, boolean canEdit) {
        if (destroyed) return;
        // v0.2.5: the error panel sits in content_area; expand the top bar so the
        // explanation is not cramped under a collapsed 32dp bar.
        setBarCollapsed(false);
        webProgress.setVisibility(View.GONE);
        errorMessage.setText(message == null ? "" : message);
        retryButton.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        editButton.setVisibility(canEdit ? View.VISIBLE : View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void openCredentialEditor() {
        if (alias == null || alias.isEmpty()) {
            finish();
            return;
        }
        Intent intent = new Intent(this, EditHostActivity.class);
        intent.putExtra(EditHostActivity.EXTRA_ALIAS, alias);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.webview_open_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        // Return to the host list; the user re-opens the host after fixing the
        // credential (the saved tunnel stays open in TunnelService meanwhile).
        finish();
    }

    // ------------------------------------------------------ external links & back

    private void openExternally(Uri uri) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(view);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.webview_external_failed, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.webview_external_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * docs/app-spec.md §7.5: a hardware back key / top-bar back arrow walks the
     * WebView history first and only then leaves the screen.
     */
    private void navigateBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        navigateBack();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        authAttempt += 1;
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            // Remove the WebView before destroying it (docs/app-spec.md §7.5.5).
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        try {
            CookieManager.getInstance().flush();
        } catch (RuntimeException ignored) {
            // Cookie persistence is best effort.
        }
        super.onDestroy();
    }

    // --------------------------------------------------------------- helpers

    /** Convert dp to physical pixels (top-bar heights and scroll thresholds). */
    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Result of the background token exchange; carries no token itself. */
    private static final class AuthResult {
        enum Failure {
            NONE,
            HTTP_REJECTED,
            HTTP_OTHER,
            NO_COOKIE,
            UNREACHABLE
        }

        final Failure failure;
        final int status;
        final String cookiePair;

        private AuthResult(Failure failure, int status, String cookiePair) {
            this.failure = failure;
            this.status = status;
            this.cookiePair = cookiePair;
        }

        static AuthResult success(String cookiePair) {
            return new AuthResult(Failure.NONE, 0, cookiePair);
        }

        static AuthResult httpRejected(int status) {
            return new AuthResult(Failure.HTTP_REJECTED, status, null);
        }

        static AuthResult httpOther(int status) {
            return new AuthResult(Failure.HTTP_OTHER, status, null);
        }

        static AuthResult noCookie() {
            return new AuthResult(Failure.NO_COOKIE, 0, null);
        }

        static AuthResult unreachable() {
            return new AuthResult(Failure.UNREACHABLE, 0, null);
        }
    }
}
