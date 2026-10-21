package dev.dsh.remotehosts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.SettingsStore;

/**
 * Global settings page (docs/app-spec.md §7.7, v0.2.5).
 *
 * Two non-secret switches, persisted immediately through {@link SettingsStore}:
 *
 * <ul>
 *   <li><b>通过外部浏览器连接</b>: replaces the old per-host "append token"
 *       checkbox. On = external browser path (copy the token URL to the
 *       sensitive clipboard, open the browser home page, the user pastes);
 *       off (default) = the built-in {@link WebViewActivity} logs in
 *       automatically. The one-shot clipboard warning is shown when the user
 *       turns it ON here, next to a permanent "?" help button.</li>
 *   <li><b>自动收起内置页面的顶部横栏</b>: lets {@link WebViewActivity} shrink
 *       its top bar to one small host-name row while reading.</li>
 * </ul>
 *
 * SECURITY: this activity never reads or writes credentials. The preferences
 * file only contains two booleans (no token, password, key or URL).
 */
public class SettingsActivity extends Activity {

    private Switch browserModeSwitch;
    private Switch collapseSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        AppBar.bind(this);
        AppBar.setTitle(this, R.string.settings_title);
        AppBar.setBackVisible(this, true);
        UiInsets.apply(this);

        browserModeSwitch = (Switch) findViewById(R.id.switch_browser_mode);
        collapseSwitch = (Switch) findViewById(R.id.switch_collapse_app_bar);
        TextView browserHelp = (TextView) findViewById(R.id.btn_browser_mode_help);
        TextView collapseHelp = (TextView) findViewById(R.id.btn_collapse_help);

        // Populate BEFORE wiring the listeners: loading the saved values must not
        // trigger the one-shot "token goes to the clipboard" warning.
        browserModeSwitch.setChecked(SettingsStore.isBrowserMode(this));
        collapseSwitch.setChecked(SettingsStore.isCollapseAppBar(this));

        browserModeSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                        SettingsStore.setBrowserMode(SettingsActivity.this, checked);
                        if (checked) showBrowserModeWarning();
                    }
                });
        collapseSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                        SettingsStore.setCollapseAppBar(SettingsActivity.this, checked);
                    }
                });

        browserHelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                showHelp(R.string.settings_browser_mode_help_title,
                        R.string.settings_browser_mode_help_message);
            }
        });
        collapseHelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                showHelp(R.string.settings_collapse_help_title,
                        R.string.settings_collapse_help_message);
            }
        });
    }

    /**
     * One-shot warning when "通过外部浏览器连接" is turned ON: the DSH token will
     * be copied to the system clipboard on every open and the browser must be
     * pasted into manually (a direct jump is blocked by the browser's SameSite
     * cookie policy). Mirrors the v0.2.4 per-host switch warning.
     */
    private void showBrowserModeWarning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_browser_mode_warn_title)
                .setMessage(R.string.settings_browser_mode_warn_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void showHelp(int titleRes, int messageRes) {
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Nothing else to do; the dialog closes itself.
                    }
                })
                .show();
    }
}
