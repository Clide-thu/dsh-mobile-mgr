package dev.dsh.remotehosts.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.CredentialUrl;
import dev.dsh.remotehosts.core.ErrorKind;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.HostKeyRecord;
import dev.dsh.remotehosts.core.HostStore;
import dev.dsh.remotehosts.core.ServiceProtocol;
import dev.dsh.remotehosts.core.SettingsStore;
import dev.dsh.remotehosts.core.TunnelService;

/**
 * Host list + the two ways of showing a host (docs/app-spec.md §7.1).
 *
 * The tunnel itself lives in {@link TunnelService}; this activity only sends
 * intents and renders the broadcast state. After the tunnel is OPEN the token
 * leaves this class in exactly one place: the sensitive clipboard URL built by
 * {@link CredentialUrl#browserUrlFor(int, String)} when the GLOBAL
 * {@link SettingsStore#isBrowserMode} preference is on (the external browser
 * path), which opens only the browser home page (the user pastes the URL). With
 * the preference off this class only passes alias + loopback port to
 * {@link WebViewActivity}; the token is read there from {@link HostStore}.
 */
public class MainActivity extends Activity implements HostAdapter.Listener {

    private static final int REQUEST_NOTIFICATIONS = 3001;
    /** docs/app-spec.md §7.1: 25 s without an OPEN/FAILED event means timeout. */
    private static final long OPEN_TIMEOUT_MS = 25000L;
    private static final String STATE_PENDING_ALIAS = "dev.dsh.remotehosts.ui.PENDING_ALIAS";

    private HostStore store;
    private ListView hostList;
    private ScrollView emptyState;
    private HostAdapter adapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver tunnelReceiver;
    private boolean receiverRegistered;
    private boolean resumed;
    private boolean notificationRequested;
    /** Alias of the host whose "open" is waiting for a tunnel event. */
    private String pendingAlias;

    private final Runnable openTimeout = new Runnable() {
        @Override
        public void run() {
            final String alias = pendingAlias;
            if (alias == null) return;
            pendingAlias = null;
            if (TunnelUiCache.state(alias) == TunnelUiCache.STATE_OPEN) {
                // The tunnel actually came up; a late event must not mask it as a failure.
                if (adapter != null) adapter.notifyDataSetChanged();
                return;
            }
            TunnelUiCache.set(alias, TunnelUiCache.STATE_FAILED, 0);
            if (adapter != null) adapter.notifyDataSetChanged();
            if (resumed && !isFinishing()) {
                showMessageDialog(getString(R.string.open_failed_title), getString(R.string.connect_timeout));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = HostStore.get(this);

        AppBar.bind(this);
        AppBar.setTitle(this, R.string.app_name);
        AppBar.setBackVisible(this, false);
        AppBar.showMoreMenu(this, R.menu.menu_main, new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return handleMenuAction(item.getItemId());
            }
        });
        UiInsets.apply(this);

        hostList = (ListView) findViewById(R.id.host_list);
        emptyState = (ScrollView) findViewById(R.id.empty_state);
        adapter = new HostAdapter(this, this);
        hostList.setAdapter(adapter);
        hostList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showRowActions(adapter.getItem(position));
                return true;
            }
        });

        Button emptyImport = (Button) findViewById(R.id.empty_import_ssh);
        emptyImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                startImport();
            }
        });
        Button emptyAdd = (Button) findViewById(R.id.empty_add_host);
        emptyAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                startEdit(null);
            }
        });
        Button emptyKeys = (Button) findViewById(R.id.empty_ssh_keys);
        emptyKeys.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                startSshKeys();
            }
        });

        // A rotation while connecting must not lose the pending "open" request;
        // the service keeps connecting in the background and will broadcast.
        if (savedInstanceState != null) {
            String restored = savedInstanceState.getString(STATE_PENDING_ALIAS);
            if (restored != null && !restored.isEmpty()) {
                pendingAlias = restored;
                TunnelUiCache.set(restored, TunnelUiCache.STATE_CONNECTING, 0);
                handler.postDelayed(openTimeout, OPEN_TIMEOUT_MS);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingAlias != null) outState.putString(STATE_PENDING_ALIAS, pendingAlias);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        syncTunnelStateFromService();
        refreshHosts();
        registerTunnelReceiver();
        requestNotificationPermission();
    }

    @Override
    protected void onPause() {
        unregisterTunnelReceiver();
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openTimeout);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ menu

    /** Handles both the custom top-bar "more" PopupMenu and any future entry point. */
    private boolean handleMenuAction(int itemId) {
        if (itemId == R.id.menu_import_ssh_config) {
            startImport();
            return true;
        }
        if (itemId == R.id.menu_add_host) {
            startEdit(null);
            return true;
        }
        if (itemId == R.id.menu_ssh_keys) {
            startSshKeys();
            return true;
        }
        if (itemId == R.id.menu_trusted_keys) {
            showTrustedKeysDialog();
            return true;
        }
        if (itemId == R.id.menu_stop_all) {
            stopAllTunnels();
            return true;
        }
        if (itemId == R.id.menu_settings) {
            startSettings();
            return true;
        }
        if (itemId == R.id.menu_about) {
            showAboutDialog();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ host list

    @Override
    public void onOpen(HostConfig host) {
        openHost(host);
    }

    @Override
    public void onEdit(HostConfig host) {
        startEdit(host == null ? null : host.alias);
    }

    private void refreshHosts() {
        List<HostConfig> hosts;
        try {
            hosts = store.list();
        } catch (RuntimeException error) {
            hosts = new ArrayList<>();
        }
        adapter.setHosts(hosts);
        boolean empty = hosts.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        hostList.setVisibility(empty ? View.GONE : View.VISIBLE);
        AppBar.setSubtitle(this, empty ? null : getString(R.string.hosts_count, hosts.size()));
    }

    private void showRowActions(final HostConfig host) {
        if (host == null) return;
        final boolean tunnelMode = host.accessMode == HostConfig.AccessMode.TUNNEL;
        final List<String> items = new ArrayList<>();
        items.add(getString(R.string.action_disconnect));
        items.add(getString(R.string.delete));
        if (tunnelMode) items.add(getString(R.string.menu_copy_open_url));
        new AlertDialog.Builder(this)
                .setTitle(host.alias)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            disconnectHost(host.alias);
                        } else if (which == 1) {
                            confirmDeleteHost(host);
                        } else if (which == 2) {
                            copyOpenUrl(host);
                        }
                    }
                })
                .show();
    }

    private void confirmDeleteHost(final HostConfig host) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_message, host.alias))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteHost(host.alias);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteHost(String alias) {
        boolean removed;
        try {
            removed = store.remove(alias);
        } catch (IOException error) {
            showMessageDialog(getString(R.string.delete_confirm_title),
                    getString(R.string.save_failed, UiErrors.describeRaw(this, error.getMessage())));
            return;
        }
        UiService.close(this, alias);
        TunnelUiCache.forget(alias);
        refreshHosts();
        Toast.makeText(this,
                removed ? getString(R.string.deleted_toast, alias) : getString(R.string.error_unknown_host),
                Toast.LENGTH_SHORT).show();
    }

    private void disconnectHost(String alias) {
        if (alias != null && alias.equals(pendingAlias)) finishPending();
        TunnelUiCache.forget(alias);
        adapter.notifyDataSetChanged();
        boolean sent = UiService.close(this, alias);
        Toast.makeText(this,
                sent ? getString(R.string.tunnel_closed_toast, alias)
                        : getString(R.string.operation_failed, getString(R.string.service_unavailable)),
                Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- opening

    private void openHost(HostConfig host) {
        if (host == null) return;
        if (pendingAlias != null) {
            Toast.makeText(this, R.string.connect_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!host.hasCredential()) {
            promptMissingCredential(host);
            return;
        }
        if (host.accessMode == HostConfig.AccessMode.DIRECT_URL) {
            openDirectUrl(host);
            return;
        }
        openTunnel(host.alias);
    }

    private void promptMissingCredential(final HostConfig host) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.no_credential_title)
                .setMessage(R.string.no_credential_message)
                .setPositiveButton(R.string.go_edit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startEdit(host.alias);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openDirectUrl(HostConfig host) {
        String url = host.directUrl == null ? "" : host.directUrl.trim();
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(view);
        } catch (ActivityNotFoundException error) {
            showMessageDialog(getString(R.string.open_failed_title), getString(R.string.no_browser));
        } catch (RuntimeException error) {
            showMessageDialog(getString(R.string.open_failed_title),
                    UiErrors.describe(this, ErrorKind.INTERNAL, error.getMessage()));
        }
    }

    private void openTunnel(String alias) {
        pendingAlias = alias;
        TunnelUiCache.set(alias, TunnelUiCache.STATE_CONNECTING, 0);
        adapter.notifyDataSetChanged();
        handler.removeCallbacks(openTimeout);
        handler.postDelayed(openTimeout, OPEN_TIMEOUT_MS);
        if (!UiService.open(this, alias)) {
            finishPending();
            TunnelUiCache.set(alias, TunnelUiCache.STATE_FAILED, 0);
            adapter.notifyDataSetChanged();
            showMessageDialog(getString(R.string.open_failed_title), getString(R.string.service_unavailable));
        }
    }

    /**
     * Called after STATE_OPEN when the tunnel for {@code alias} is ready.
     *
     * v0.2.4/v0.2.5 (docs/app-spec.md §7.1): the tunnel URL is never handed to an
     * external browser through {@code ACTION_VIEW}. The GLOBAL
     * {@link SettingsStore#isBrowserMode} preference picks one of two paths:
     * <ul>
     *   <li>on (external browser): copy the exact
     *       {@link CredentialUrl#browserUrlFor(int, String)} URL into the
     *       sensitive clipboard, then open the default browser's home page and
     *       let the user paste it (a direct jump would lose the remote
     *       SameSite=Strict login cookie). No dialog at all.</li>
     *   <li>off (built-in WebView, default): start {@link WebViewActivity}, which
     *       performs the token exchange and cookie injection by itself.</li>
     * </ul>
     */
    private void launchBrowser(String alias, int localPort) {
        HostConfig host = store.find(alias);
        if (host == null) {
            showMessageDialog(getString(R.string.open_failed_title), getString(R.string.error_unknown_host));
            refreshHosts();
            return;
        }
        if (host.accessMode == HostConfig.AccessMode.DIRECT_URL) {
            // The host was edited while its tunnel was still coming up.
            openDirectUrl(host);
            return;
        }
        if (SettingsStore.isBrowserMode(this)) {
            openWithBrowserHandoff(host, localPort);
        } else {
            openInWebView(host, localPort);
        }
    }

    /**
     * A path (v0.2.4): copy the token URL to the sensitive clipboard and open
     * the browser home page so the user can paste it. No dialog, and no
     * ACTION_VIEW fallback with the URL.
     */
    private void openWithBrowserHandoff(HostConfig host, int localPort) {
        String token = host.token == null ? "" : host.token;
        if (token.isEmpty()) {
            showMessageDialog(getString(R.string.open_failed_title), getString(R.string.error_needs_credentials));
            return;
        }
        final String url;
        try {
            // The ONLY place a token is put into a URL (docs/app-spec.md §2).
            url = CredentialUrl.browserUrlFor(localPort, token);
        } catch (IllegalArgumentException error) {
            showMessageDialog(getString(R.string.open_failed_title), getString(R.string.error_internal));
            return;
        }
        if (!copyUrlToClipboard(url, false)) {
            Toast.makeText(this, R.string.append_token_copy_failed, Toast.LENGTH_LONG).show();
            return;
        }
        boolean opened = openBrowserHome();
        Toast.makeText(this,
                opened ? R.string.append_token_copied_hint : R.string.append_token_browser_failed,
                Toast.LENGTH_LONG).show();
    }

    /**
     * B path (v0.2.4): start the built-in WebView. The Intent intentionally
     * carries only {@code alias} + {@code localPort} — never the token.
     */
    private void openInWebView(HostConfig host, int localPort) {
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WebViewActivity.EXTRA_ALIAS, host.alias);
        intent.putExtra(WebViewActivity.EXTRA_LOCAL_PORT, localPort);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            showMessageDialog(getString(R.string.open_failed_title), getString(R.string.webview_open_failed));
        }
    }

    /**
     * Open the default browser's home page (an empty new tab) so the user can
     * paste the copied URL. v0.2.4: failure only shows a Toast — there is
     * deliberately no ACTION_VIEW fallback carrying the tunnel URL.
     *
     * @return true when the intent was accepted by the system
     */
    private boolean openBrowserHome() {
        try {
            Intent home = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_BROWSER);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            return true;
        } catch (ActivityNotFoundException error) {
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    // ---------------------------------------------- copy open URL (v0.2.2)

    /**
     * Long-press action: copy the URL that would be handed to the browser,
     * including the token, so the user can paste it into any browser/profile.
     * Only available while this host's tunnel is OPEN (the loopback port is only
     * meaningful then). The token never leaves this method except into the
     * clipboard, behind an explicit warning and the API 33+ sensitive flag.
     */
    private void copyOpenUrl(final HostConfig host) {
        if (host == null || host.accessMode != HostConfig.AccessMode.TUNNEL) return;
        if (TunnelUiCache.state(host.alias) != TunnelUiCache.STATE_OPEN) {
            Toast.makeText(this, R.string.copy_open_url_need_tunnel, Toast.LENGTH_SHORT).show();
            return;
        }
        final int localPort = TunnelUiCache.localPort(host.alias);
        if (localPort <= 0) {
            Toast.makeText(this, R.string.copy_open_url_need_tunnel, Toast.LENGTH_SHORT).show();
            return;
        }
        HostConfig current = store.find(host.alias);
        final String token = current == null ? host.token : current.token;
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, R.string.copy_open_url_need_token, Toast.LENGTH_SHORT).show();
            return;
        }
        final String url;
        try {
            url = CredentialUrl.browserUrlFor(localPort, token);
        } catch (IllegalArgumentException error) {
            showMessageDialog(getString(R.string.copy_open_url_warning_title), getString(R.string.error_internal));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.copy_open_url_warning_title)
                .setMessage(R.string.copy_open_url_warning_message)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyUrlToClipboard(url);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyUrlToClipboard(String url) {
        copyUrlToClipboard(url, true);
    }

    /**
     * Copy a URL to the (sensitive) clipboard.
     *
     * @param showSuccessToast false when the caller shows its own success toast
     * @return true when the clipboard was updated
     */
    private boolean copyUrlToClipboard(String url, boolean showSuccessToast) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, R.string.copy_open_url_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
        ClipData clip = ClipData.newPlainText(getString(R.string.menu_copy_open_url), url);
        if (Build.VERSION.SDK_INT >= 33) {
            // Ask the system not to show a clipboard preview for the token URL.
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        try {
            manager.setPrimaryClip(clip);
            if (showSuccessToast) {
                Toast.makeText(this, R.string.copy_open_url_done, Toast.LENGTH_LONG).show();
            }
            return true;
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.copy_open_url_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void finishPending() {
        pendingAlias = null;
        handler.removeCallbacks(openTimeout);
    }

    // ---------------------------------------------------- service broadcasts

    private void registerTunnelReceiver() {
        if (receiverRegistered) return;
        tunnelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleTunnelEvent(intent);
            }
        };
        IntentFilter filter = new IntentFilter(ServiceProtocol.EVENT_TUNNEL);
        if (Build.VERSION.SDK_INT >= 33) {
            // API 33+: non-system receivers must declare their export policy.
            registerReceiver(tunnelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(tunnelReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterTunnelReceiver() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(tunnelReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already gone; nothing to do.
        }
        receiverRegistered = false;
        tunnelReceiver = null;
    }

    private void syncTunnelStateFromService() {
        Map<String, Integer> snapshot = null;
        try {
            snapshot = TunnelService.openTunnelsSnapshot();
        } catch (Exception ignored) {
            // Service class unavailable in some environments; cache is best effort.
        }
        TunnelUiCache.syncOpenFromService(snapshot);
    }

    private void handleTunnelEvent(Intent intent) {
        if (intent == null) return;
        String state = intent.getStringExtra(ServiceProtocol.EXTRA_STATE);
        String alias = intent.getStringExtra(ServiceProtocol.EXTRA_ALIAS);
        if (state == null || alias == null) return;

        if (ServiceProtocol.STATE_STARTING.equals(state)) {
            TunnelUiCache.set(alias, TunnelUiCache.STATE_CONNECTING, 0);
            adapter.notifyDataSetChanged();
            return;
        }
        if (ServiceProtocol.STATE_OPEN.equals(state)) {
            int localPort = intent.getIntExtra(ServiceProtocol.EXTRA_LOCAL_PORT, 0);
            TunnelUiCache.set(alias, TunnelUiCache.STATE_OPEN, localPort);
            adapter.notifyDataSetChanged();
            if (alias.equals(pendingAlias)) {
                finishPending();
                if (resumed && !isFinishing()) {
                    if (localPort > 0) {
                        launchBrowser(alias, localPort);
                    } else {
                        showMessageDialog(getString(R.string.open_failed_title), getString(R.string.error_internal));
                    }
                }
            }
            return;
        }
        if (ServiceProtocol.STATE_NEEDS_HOSTKEY.equals(state)) {
            TunnelUiCache.set(alias, TunnelUiCache.STATE_CONNECTING, 0);
            adapter.notifyDataSetChanged();
            if (resumed && !isFinishing()) {
                // The decision dialog is the only way forward; pause the timeout
                // while it is open and restart it if the user trusts the key.
                handler.removeCallbacks(openTimeout);
                showHostKeyDialog(alias, intent);
            }
            return;
        }
        if (ServiceProtocol.STATE_FAILED.equals(state)) {
            String kindId = intent.getStringExtra(ServiceProtocol.EXTRA_ERROR_KIND);
            String message = intent.getStringExtra(ServiceProtocol.EXTRA_ERROR_MESSAGE);
            TunnelUiCache.set(alias, TunnelUiCache.STATE_FAILED, 0);
            adapter.notifyDataSetChanged();
            if (alias.equals(pendingAlias)) {
                finishPending();
                showOpenFailure(alias, ErrorKind.fromId(kindId), message);
            }
            return;
        }
        if (ServiceProtocol.STATE_CLOSED.equals(state)) {
            TunnelUiCache.forget(alias);
            adapter.notifyDataSetChanged();
            if (alias.equals(pendingAlias)) {
                finishPending();
                if (resumed && !isFinishing()) {
                    showMessageDialog(getString(R.string.open_failed_title), getString(R.string.connect_closed));
                }
            }
        }
    }

    private void showHostKeyDialog(final String alias, Intent intent) {
        HostConfig host = store.find(alias);
        String hostPort = intent.getStringExtra(ServiceProtocol.EXTRA_HOST_PORT);
        if (hostPort == null || hostPort.isEmpty()) {
            hostPort = host == null ? alias : host.resolvedHostName() + ":" + host.port;
        }
        String keyType = nvl(intent.getStringExtra(ServiceProtocol.EXTRA_KEY_TYPE));
        final String keyBase64 = nvl(intent.getStringExtra(ServiceProtocol.EXTRA_KEY_B64));
        String fingerprint = nvl(intent.getStringExtra(ServiceProtocol.EXTRA_FINGERPRINT));
        int extraRemotePort = intent.getIntExtra(ServiceProtocol.EXTRA_REMOTE_PORT, 0);
        final int remotePort = extraRemotePort > 0 ? extraRemotePort : (host == null ? 0 : host.remotePort);
        final String finalHostPort = hostPort;
        final String finalKeyType = keyType;
        final String finalFingerprint = fingerprint;

        new AlertDialog.Builder(this)
                .setTitle(R.string.host_key_title)
                .setMessage(getString(R.string.host_key_message, hostPort, keyType, fingerprint))
                .setPositiveButton(R.string.host_key_trust, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        TunnelUiCache.set(alias, TunnelUiCache.STATE_CONNECTING, 0);
                        adapter.notifyDataSetChanged();
                        handler.removeCallbacks(openTimeout);
                        handler.postDelayed(openTimeout, OPEN_TIMEOUT_MS);
                        boolean started = UiService.trustHostKey(
                                MainActivity.this, alias, finalHostPort, finalKeyType,
                                keyBase64, finalFingerprint, remotePort);
                        if (!started) {
                            finishPending();
                            TunnelUiCache.set(alias, TunnelUiCache.STATE_FAILED, 0);
                            adapter.notifyDataSetChanged();
                            showMessageDialog(getString(R.string.open_failed_title),
                                    getString(R.string.service_unavailable));
                        }
                    }
                })
                .setNegativeButton(R.string.host_key_reject, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finishPending();
                        TunnelUiCache.forget(alias);
                        adapter.notifyDataSetChanged();
                        UiService.close(MainActivity.this, alias);
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void showOpenFailure(String alias, ErrorKind kind, String message) {
        if (!resumed || isFinishing()) return;
        String text = UiErrors.describe(this, kind, message);
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_failed_title)
                .setMessage(text)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.go_edit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startEdit(alias);
                    }
                })
                .show();
    }

    // ------------------------------------------------------ stop all / menus

    private void stopAllTunnels() {
        boolean running = false;
        try {
            running = TunnelService.isRunning();
        } catch (Exception ignored) {
            // Treat as not running.
        }
        boolean active = TunnelUiCache.hasActive();
        finishPending();
        TunnelUiCache.clear();
        adapter.notifyDataSetChanged();
        if (!running && !active) {
            Toast.makeText(this, R.string.no_tunnel_running, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean sent = UiService.stopAll(this);
        Toast.makeText(this,
                sent ? getString(R.string.all_tunnels_stopped)
                        : getString(R.string.operation_failed, getString(R.string.service_unavailable)),
                Toast.LENGTH_SHORT).show();
    }

    private void showTrustedKeysDialog() {
        final Map<String, HostKeyRecord> entries;
        try {
            entries = store.trustedHostKeyEntries();
        } catch (Exception error) {
            showMessageDialog(getString(R.string.trusted_keys_title),
                    UiErrors.describeRaw(this, error.getMessage()));
            return;
        }
        if (entries == null || entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.trusted_keys_title)
                    .setMessage(R.string.trusted_keys_empty)
                    .setPositiveButton(R.string.close, null)
                    .show();
            return;
        }
        View content = getLayoutInflater().inflate(R.layout.dialog_trusted_keys, null);
        // Bound the dialog height so a long trust list scrolls instead of
        // overflowing the screen.
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.6f);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        LinearLayout container = (LinearLayout) content.findViewById(R.id.trusted_keys_container);
        for (Map.Entry<String, HostKeyRecord> entry : entries.entrySet()) {
            container.addView(buildTrustedKeyRow(entry.getKey(), entry.getValue()));
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.trusted_keys_count, entries.size()))
                .setView(content)
                .setPositiveButton(R.string.trusted_keys_clear_all, null)
                .setNegativeButton(R.string.close, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                confirmClearTrustedKeys(dialog);
            }
        });
    }

    private View buildTrustedKeyRow(String hostPortKey, HostKeyRecord record) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        row.setPadding(0, pad, 0, pad);

        TextView title = new TextView(this);
        title.setText(hostPortKey == null ? "?" : hostPortKey);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        row.addView(title);

        StringBuilder meta = new StringBuilder();
        if (record != null && record.keyType != null && !record.keyType.isEmpty()) {
            meta.append(record.keyType);
        } else {
            meta.append("?");
        }
        if (record != null && record.fingerprint != null && !record.fingerprint.isEmpty()) {
            meta.append('\n').append(getString(R.string.trusted_keys_fingerprint, record.fingerprint));
        }
        if (record != null && record.addedAt != null && !record.addedAt.isEmpty()) {
            meta.append('\n').append(getString(R.string.trusted_keys_added_at, record.addedAt));
        }
        TextView details = new TextView(this);
        details.setText(meta);
        details.setTextSize(12f);
        details.setTextColor(getColor(R.color.text_secondary));
        row.addView(details);

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.card_stroke));
        row.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return row;
    }

    private void confirmClearTrustedKeys(final AlertDialog parent) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trusted_keys_clear_confirm_title)
                .setMessage(R.string.trusted_keys_clear_confirm_message)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            int count = store.clearTrustedHostKeys();
                            parent.dismiss();
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.trusted_keys_cleared, count), Toast.LENGTH_SHORT).show();
                        } catch (IOException error) {
                            showMessageDialog(getString(R.string.trusted_keys_title),
                                    getString(R.string.trusted_keys_clear_failed,
                                            UiErrors.describeRaw(MainActivity.this, error.getMessage())));
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAboutDialog() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep the placeholder.
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, version))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------- utilities

    private void startImport() {
        startActivity(new Intent(this, ImportConfigActivity.class));
    }

    private void startSshKeys() {
        startActivity(new Intent(this, SshKeysActivity.class));
    }

    private void startSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void startEdit(String alias) {
        Intent intent = new Intent(this, EditHostActivity.class);
        if (alias != null && !alias.isEmpty()) intent.putExtra(EditHostActivity.EXTRA_ALIAS, alias);
        startActivity(intent);
    }

    private void showMessageDialog(String title, String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------- POST_NOTIFICATIONS (13+)

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (notificationRequested) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationRequested = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS
                && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // Denial never blocks opening tunnels; it only silences the notification.
            Toast.makeText(this, R.string.notifications_denied_hint, Toast.LENGTH_LONG).show();
        }
    }
}
