package dev.dsh.remotehosts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.CredentialUrl;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.HostKeyRecord;
import dev.dsh.remotehosts.core.HostStore;
import dev.dsh.remotehosts.core.SshKeyRecord;

/**
 * Add / edit one host (docs/app-spec.md §7.2, v0.2.0).
 *
 * Security notes:
 *  - An already-saved DSH token is never re-displayed: the credential field
 *    starts empty and shows only a masked hint; leaving it empty keeps the
 *    existing port+token.
 *  - Private-key auth selects a key from the app key store by {@code keyId}; the
 *    editor no longer handles inline private keys or passphrases.
 *  - Password / credential / direct-URL fields have autofill and saved-state
 *    persistence disabled.
 *  - Validation happens before any write; TUNNEL mode stores only port + token.
 *  - v0.2.5: the per-host "append token" hand-off switch was removed; whether a
 *    host opens in the external browser or in the built-in WebView is a global
 *    setting on the {@link SettingsActivity} page (docs/app-spec.md §7.7).
 */
public class EditHostActivity extends Activity {

    /** Extra: alias of the host to edit; absent means "create new". */
    public static final String EXTRA_ALIAS = "dev.dsh.remotehosts.extra.EDIT_ALIAS";

    private HostStore store;
    /** Existing host when editing; null when creating a new one. */
    private HostConfig existing;
    private boolean editing;

    private EditText editAlias;
    private EditText editHostName;
    private EditText editUser;
    private EditText editPort;
    private EditText editPassword;
    private EditText editProxyJump;
    private EditText editCredentialUrl;
    private EditText editDirectUrl;

    private RadioGroup authGroup;
    private RadioGroup accessGroup;
    private LinearLayout groupPassword;
    private LinearLayout groupKey;
    private LinearLayout groupCredential;
    private LinearLayout groupDirect;
    private Spinner spinnerKey;
    private TextView textKeyHint;
    private TextView textCredentialStatus;
    private TextView textDirectStatus;
    private TextView textIdentityInfo;
    private Button btnDelete;
    private Button btnResetHostKey;

    /** Snapshot of the key store backing the spinner (parallel to its entries). */
    private List<SshKeyRecord> keyRecords = new ArrayList<>();
    /** keyId currently chosen in the spinner (kept across onResume reloads). */
    private String selectedKeyId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_host);
        store = HostStore.get(this);

        String alias = getIntent().getStringExtra(EXTRA_ALIAS);
        if (alias != null && !alias.isEmpty()) {
            existing = store.find(alias);
        }
        editing = existing != null;

        AppBar.bind(this);
        AppBar.setTitle(this, editing ? R.string.edit_title_edit : R.string.edit_title_new);
        AppBar.setBackVisible(this, true);
        UiInsets.apply(this);

        bindViews();
        hardenSensitiveInputs();
        populateForm();
        loadKeys();
        wireListeners();
        updateModeVisibility();
        validateCredentialField();
        validateDirectField();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Keys may have been generated/imported in SshKeysActivity; refresh the
        // spinner while keeping the current selection when possible.
        if (spinnerKey != null) loadKeys();
    }

    private void bindViews() {
        editAlias = (EditText) findViewById(R.id.edit_alias);
        editHostName = (EditText) findViewById(R.id.edit_host_name);
        editUser = (EditText) findViewById(R.id.edit_user);
        editPort = (EditText) findViewById(R.id.edit_port);
        editPassword = (EditText) findViewById(R.id.edit_password);
        editProxyJump = (EditText) findViewById(R.id.edit_proxy_jump);
        editCredentialUrl = (EditText) findViewById(R.id.edit_credential_url);
        editDirectUrl = (EditText) findViewById(R.id.edit_direct_url);

        authGroup = (RadioGroup) findViewById(R.id.auth_group);
        accessGroup = (RadioGroup) findViewById(R.id.access_group);
        groupPassword = (LinearLayout) findViewById(R.id.group_password);
        groupKey = (LinearLayout) findViewById(R.id.group_key);
        groupCredential = (LinearLayout) findViewById(R.id.group_credential);
        groupDirect = (LinearLayout) findViewById(R.id.group_direct);
        spinnerKey = (Spinner) findViewById(R.id.spinner_key);
        textKeyHint = (TextView) findViewById(R.id.text_key_hint);
        textCredentialStatus = (TextView) findViewById(R.id.text_credential_status);
        textDirectStatus = (TextView) findViewById(R.id.text_direct_status);
        textIdentityInfo = (TextView) findViewById(R.id.text_identity_info);
        btnDelete = (Button) findViewById(R.id.btn_delete);
        btnResetHostKey = (Button) findViewById(R.id.btn_reset_host_key);

        btnDelete.setVisibility(editing ? View.VISIBLE : View.GONE);
        btnResetHostKey.setVisibility(editing ? View.VISIBLE : View.GONE);

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                save();
            }
        });
        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                finish();
            }
        });
        findViewById(R.id.btn_manage_keys).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                startActivity(new Intent(EditHostActivity.this, SshKeysActivity.class));
            }
        });
        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                confirmDelete();
            }
        });
        btnResetHostKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                confirmResetHostKey();
            }
        });
    }

    /**
     * Load key-store entries into the spinner and restore the selection from the
     * saved host / current in-dialog choice. Safe when the key store is empty.
     */
    private void loadKeys() {
        keyRecords = store.listSshKeys();
        List<String> labels = new ArrayList<>();
        for (SshKeyRecord key : keyRecords) {
            labels.add(getString(R.string.ssh_key_spinner_item,
                    key.label, UiKeys.shortFingerprint(key.fingerprint)));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerKey.setAdapter(adapter);

        String wanted = selectedKeyId;
        if ((wanted == null || wanted.isEmpty()) && existing != null) {
            wanted = existing.keyId == null ? "" : existing.keyId;
        }
        int preselect = -1;
        for (int index = 0; index < keyRecords.size(); index += 1) {
            if (keyRecords.get(index).id.equals(wanted)) {
                preselect = index;
                break;
            }
        }
        if (preselect >= 0) {
            spinnerKey.setSelection(preselect);
            selectedKeyId = keyRecords.get(preselect).id;
        } else {
            selectedKeyId = "";
            if (keyRecords.isEmpty()) {
                if (authGroup.getCheckedRadioButtonId() == R.id.radio_auth_key) {
                    textKeyHint.setText(R.string.ssh_key_no_keys_hint);
                    textKeyHint.setTextColor(getColor(R.color.status_warn));
                } else {
                    textKeyHint.setText(R.string.ssh_key_select_hint);
                    textKeyHint.setTextColor(getColor(R.color.text_secondary));
                }
            } else {
                textKeyHint.setText(R.string.ssh_key_select_hint);
                textKeyHint.setTextColor(getColor(R.color.text_secondary));
            }
        }
        updateKeyHint();
    }

    private void updateKeyHint() {
        int position = spinnerKey.getSelectedItemPosition();
        if (position >= 0 && position < keyRecords.size()) {
            selectedKeyId = keyRecords.get(position).id;
            SshKeyRecord key = keyRecords.get(position);
            textKeyHint.setText(getString(R.string.ssh_key_selected_hint,
                    key.label, key.keyType, key.fingerprint));
            textKeyHint.setTextColor(getColor(R.color.text_secondary));
        } else if (keyRecords.isEmpty()) {
            textKeyHint.setText(R.string.ssh_key_no_keys_hint);
            textKeyHint.setTextColor(getColor(R.color.status_warn));
        } else {
            textKeyHint.setText(R.string.ssh_key_select_hint);
            textKeyHint.setTextColor(getColor(R.color.text_secondary));
        }
    }

    private void wireListeners() {
        authGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                updateModeVisibility();
            }
        });
        accessGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                updateModeVisibility();
                validateCredentialField();
                validateDirectField();
            }
        });
        spinnerKey.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateKeyHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedKeyId = "";
                updateKeyHint();
            }
        });
        // v0.2.5: show the security warning only when the user switches the
        // hand-off style from "App 内打开" to "浏览器粘贴" themselves.
        editCredentialUrl.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                validateCredentialField();
            }
        });
        editDirectUrl.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                validateDirectField();
            }
        });
    }

    /**
     * Security hardening for secret-bearing fields (docs/android-ssh-guide.md
     * §5.3): never offer them to the system autofill service and never let the
     * framework persist them in the saved-instance-state bundle.
     */
    private void hardenSensitiveInputs() {
        editPassword.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        editCredentialUrl.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editDirectUrl.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editPassword.setSaveEnabled(false);
        editCredentialUrl.setSaveEnabled(false);
        editDirectUrl.setSaveEnabled(false);
    }

    private void populateForm() {
        if (!editing) {
            editPort.setText("22");
            return;
        }
        editAlias.setText(existing.alias);
        editHostName.setText(existing.hostName);
        editUser.setText(existing.user);
        editPort.setText(String.valueOf(existing.port));
        editPassword.setText(existing.password);
        editProxyJump.setText(existing.proxyJump);
        editDirectUrl.setText(existing.directUrl);
        // Never re-display a saved token: an empty field keeps the stored one.
        editCredentialUrl.setText("");
        if (existing.authType == HostConfig.AuthType.PRIVATE_KEY) {
            ((android.widget.RadioButton) findViewById(R.id.radio_auth_key)).setChecked(true);
        } else {
            ((android.widget.RadioButton) findViewById(R.id.radio_auth_password)).setChecked(true);
        }
        if (existing.accessMode == HostConfig.AccessMode.DIRECT_URL) {
            ((android.widget.RadioButton) findViewById(R.id.radio_access_direct)).setChecked(true);
        } else {
            ((android.widget.RadioButton) findViewById(R.id.radio_access_tunnel)).setChecked(true);
        }
        if (existing.identityFiles != null && !existing.identityFiles.isEmpty()) {
            textIdentityInfo.setText(getString(R.string.identity_files_info,
                    TextUtils.join("\n", existing.identityFiles)));
            textIdentityInfo.setVisibility(View.VISIBLE);
        }
    }

    private void updateModeVisibility() {
        boolean keyAuth = authGroup.getCheckedRadioButtonId() == R.id.radio_auth_key;
        groupPassword.setVisibility(keyAuth ? View.GONE : View.VISIBLE);
        groupKey.setVisibility(keyAuth ? View.VISIBLE : View.GONE);
        boolean tunnel = accessGroup.getCheckedRadioButtonId() != R.id.radio_access_direct;
        groupCredential.setVisibility(tunnel ? View.VISIBLE : View.GONE);
        groupDirect.setVisibility(tunnel ? View.GONE : View.VISIBLE);
        if (keyAuth && textKeyHint != null) {
            if (keyRecords.isEmpty()) {
                textKeyHint.setText(R.string.ssh_key_no_keys_hint);
                textKeyHint.setTextColor(getColor(R.color.status_warn));
            } else {
                updateKeyHint();
            }
        }
    }

    // --------------------------------- v0.2.5: global open mode lives in Settings
    // The old per-host append-token switch (and its "?" help / one-shot warning)
    // was removed; the open mode is global on the SettingsActivity page.

    // ------------------------------------------------------------ validation

    private void validateCredentialField() {
        String text = editCredentialUrl.getText().toString().trim();
        if (text.isEmpty()) {
            if (editing && existing.remotePort > 0 && existing.token != null && !existing.token.isEmpty()) {
                textCredentialStatus.setText(getString(R.string.credential_saved_masked,
                        existing.remotePort, CredentialUrl.maskToken(existing.token)));
            } else {
                textCredentialStatus.setText(R.string.credential_empty_hint);
            }
            textCredentialStatus.setTextColor(getColor(R.color.text_secondary));
            editCredentialUrl.setError(null);
            return;
        }
        CredentialUrl.Parsed parsed = CredentialUrl.parse(text);
        if (parsed.ok) {
            textCredentialStatus.setText(getString(R.string.credential_ok,
                    parsed.remotePort, CredentialUrl.maskToken(parsed.token)));
            textCredentialStatus.setTextColor(getColor(R.color.status_ok));
            editCredentialUrl.setError(null);
        } else {
            textCredentialStatus.setText(getString(R.string.credential_invalid, parsed.reason));
            textCredentialStatus.setTextColor(getColor(R.color.status_error));
        }
    }

    private void validateDirectField() {
        String text = editDirectUrl.getText().toString().trim();
        if (text.isEmpty()) {
            textDirectStatus.setText("");
            editDirectUrl.setError(null);
            return;
        }
        if (CredentialUrl.isValidDirectUrl(text)) {
            textDirectStatus.setText(R.string.direct_url_ok);
            textDirectStatus.setTextColor(getColor(R.color.status_ok));
            editDirectUrl.setError(null);
        } else {
            textDirectStatus.setText(R.string.direct_url_invalid);
            textDirectStatus.setTextColor(getColor(R.color.status_error));
            editDirectUrl.setError(null);
        }
    }

    // ----------------------------------------------------------------- save

    private void save() {
        clearErrors();
        String alias = value(editAlias);
        if (alias.isEmpty()) {
            editAlias.setError(getString(R.string.field_required, getString(R.string.label_alias)));
            editAlias.requestFocus();
            return;
        }
        HostConfig clash = store.find(alias);
        if (clash != null && (!editing || !alias.equals(existing.alias))) {
            editAlias.setError(getString(R.string.alias_exists, alias));
            editAlias.requestFocus();
            return;
        }
        String user = value(editUser);
        if (user.isEmpty()) {
            editUser.setError(getString(R.string.field_required, getString(R.string.label_user)));
            editUser.requestFocus();
            return;
        }
        int port = 22;
        String portText = value(editPort);
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException error) {
                port = -1;
            }
            if (port < 1 || port > 65535) {
                editPort.setError(getString(R.string.field_invalid_port));
                editPort.requestFocus();
                return;
            }
        }

        boolean keyAuth = authGroup.getCheckedRadioButtonId() == R.id.radio_auth_key;
        String password = editPassword.getText().toString();
        String keyId = "";
        if (keyAuth) {
            int position = spinnerKey.getSelectedItemPosition();
            if (position < 0 || position >= keyRecords.size()) {
                textKeyHint.setText(R.string.ssh_key_required);
                textKeyHint.setTextColor(getColor(R.color.status_error));
                Toast.makeText(this, R.string.ssh_key_required, Toast.LENGTH_LONG).show();
                return;
            }
            keyId = keyRecords.get(position).id;
            selectedKeyId = keyId;
        } else if (password.isEmpty()) {
            editPassword.setError(getString(R.string.auth_required_password));
            editPassword.requestFocus();
            return;
        }

        boolean tunnel = accessGroup.getCheckedRadioButtonId() != R.id.radio_access_direct;
        String credentialText = value(editCredentialUrl);
        String directText = value(editDirectUrl);
        int remotePort = 0;
        String token = "";
        if (tunnel) {
            if (!credentialText.isEmpty()) {
                CredentialUrl.Parsed parsed = CredentialUrl.parse(credentialText);
                if (!parsed.ok) {
                    editCredentialUrl.setError(getString(R.string.credential_invalid, parsed.reason));
                    editCredentialUrl.requestFocus();
                    return;
                }
                remotePort = parsed.remotePort;
                token = parsed.token;
            } else if (editing && existing.remotePort > 0 && existing.token != null && !existing.token.isEmpty()) {
                // Keep the already-saved credential when the field is left empty.
                remotePort = existing.remotePort;
                token = existing.token;
            }
        } else if (!CredentialUrl.isValidDirectUrl(directText)) {
            editDirectUrl.setError(getString(R.string.direct_url_invalid));
            editDirectUrl.requestFocus();
            return;
        }

        HostConfig host = editing ? existing.copy() : new HostConfig();
        host.alias = alias;
        host.hostName = value(editHostName);
        host.user = user;
        host.port = port;
        host.authType = keyAuth ? HostConfig.AuthType.PRIVATE_KEY : HostConfig.AuthType.PASSWORD;
        // v0.2.0: only the selected key id is stored; inline key material is legacy.
        host.keyId = keyAuth ? keyId : "";
        host.password = keyAuth ? "" : password;
        host.privateKey = "";
        host.keyPassphrase = "";
        host.proxyJump = value(editProxyJump);
        if (tunnel) {
            host.accessMode = HostConfig.AccessMode.TUNNEL;
            host.remotePort = remotePort;
            host.token = token;
        } else {
            host.accessMode = HostConfig.AccessMode.DIRECT_URL;
            host.directUrl = directText;
        }

        try {
            store.upsert(host);
            if (editing && !alias.equals(existing.alias)) {
                // Alias changed: drop the old entry (and its trusted host key).
                store.remove(existing.alias);
                UiService.close(this, existing.alias);
                TunnelUiCache.forget(existing.alias);
            }
        } catch (IOException error) {
            showMessageDialog(getString(R.string.save),
                    getString(R.string.save_failed, UiErrors.describeRaw(this, error.getMessage())));
            return;
        }
        Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void clearErrors() {
        editAlias.setError(null);
        editUser.setError(null);
        editPort.setError(null);
        editPassword.setError(null);
        editCredentialUrl.setError(null);
        editDirectUrl.setError(null);
    }

    // --------------------------------------------------------------- delete

    private void confirmDelete() {
        if (!editing || existing == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_message, existing.alias))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteHost();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteHost() {
        if (!editing || existing == null) return;
        try {
            store.remove(existing.alias);
        } catch (IOException error) {
            showMessageDialog(getString(R.string.delete_confirm_title),
                    getString(R.string.save_failed, UiErrors.describeRaw(this, error.getMessage())));
            return;
        }
        UiService.close(this, existing.alias);
        TunnelUiCache.forget(existing.alias);
        Toast.makeText(this, getString(R.string.deleted_toast, existing.alias), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    // ------------------------------------------------------ host key reset

    private void confirmResetHostKey() {
        if (!editing || existing == null) return;
        final String hostPortKey = HostStore.hostPortKeyFor(existing);
        if (hostPortKey == null || hostPortKey.isEmpty()) {
            Toast.makeText(this, R.string.reset_key_none, Toast.LENGTH_SHORT).show();
            return;
        }
        HostKeyRecord record = store.trustedHostKeyRecord(hostPortKey);
        if (record == null) {
            Toast.makeText(this, R.string.reset_key_none, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_key_confirm_title)
                .setMessage(R.string.reset_key_confirm_message)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            boolean removed = store.clearTrustedHostKey(hostPortKey);
                            Toast.makeText(EditHostActivity.this,
                                    removed ? getString(R.string.reset_key_done, existing.alias)
                                            : getString(R.string.reset_key_none),
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException error) {
                            Toast.makeText(EditHostActivity.this,
                                    getString(R.string.reset_key_failed,
                                            UiErrors.describeRaw(EditHostActivity.this, error.getMessage())),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // -------------------------------------------------------------- helpers

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private void showMessageDialog(String title, String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** Empty {@link TextWatcher} adapter so callers only override what they need. */
    private abstract static class TextWatcherAdapter implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable editable) {
        }
    }
}
