package dev.dsh.remotehosts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.AppError;
import dev.dsh.remotehosts.core.ErrorKind;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.HostStore;
import dev.dsh.remotehosts.core.SshKeyManager;
import dev.dsh.remotehosts.core.SshKeyRecord;

/**
 * SSH key manager UI (docs/app-spec.md §7.4, v0.2.0).
 *
 * Shows the app-managed key pairs; lets the user generate an RSA pair, import an
 * existing pair (with optional passphrase / public-key match check), inspect a
 * key, copy its public key, and — only after an explicit confirmation — reveal
 * or copy the private key (marked as sensitive on the clipboard from API 33).
 *
 * No private key or passphrase is ever logged, put into an Intent extra, or
 * rendered in a list row; the only output paths are the detail dialog and the
 * clipboard, after the user confirms.
 */
public class SshKeysActivity extends Activity {

    private static final int REQUEST_PICK_PRIVATE_KEY = 3301;
    private static final float IMPORT_DIALOG_HEIGHT_RATIO = 0.75f;
    private static final float DETAIL_DIALOG_HEIGHT_RATIO = 0.72f;
    private static final float PUBLIC_KEY_DIALOG_HEIGHT_RATIO = 0.55f;

    private HostStore store;
    private SshKeyAdapter adapter;
    private ListView keyList;
    private ScrollView emptyState;

    // Import dialog state; kept while the SAF file picker is open.
    private AlertDialog importDialog;
    private EditText importLabelField;
    private EditText importPrivateField;
    private EditText importPassphraseField;
    private EditText importPublicField;
    private TextView importStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh_keys);
        store = HostStore.get(this);

        AppBar.bind(this);
        AppBar.setTitle(this, R.string.ssh_keys_title);
        AppBar.setBackVisible(this, true);
        UiInsets.apply(this);

        adapter = new SshKeyAdapter(this);
        keyList = (ListView) findViewById(R.id.ssh_key_list);
        keyList.setAdapter(adapter);
        keyList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                SshKeyRecord key = adapter.getItem(position).key;
                showDetailDialog(key.id);
            }
        });
        emptyState = (ScrollView) findViewById(R.id.ssh_key_empty);

        View.OnClickListener generate = new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                showGenerateDialog();
            }
        };
        View.OnClickListener importPair = new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                showImportDialog();
            }
        };
        findViewById(R.id.btn_generate_key).setOnClickListener(generate);
        findViewById(R.id.btn_import_key).setOnClickListener(importPair);
        findViewById(R.id.empty_generate_key).setOnClickListener(generate);
        findViewById(R.id.empty_import_key).setOnClickListener(importPair);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshKeys();
    }

    @Override
    protected void onDestroy() {
        if (importDialog != null && importDialog.isShowing()) importDialog.dismiss();
        clearImportDialogRefs();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ list

    private void refreshKeys() {
        List<SshKeyRecord> keys = store.listSshKeys();
        Map<String, Integer> usage = new HashMap<>();
        for (HostConfig host : store.list()) {
            String keyId = host.keyId;
            if (keyId == null || keyId.isEmpty()) continue;
            Integer count = usage.get(keyId);
            usage.put(keyId, count == null ? 1 : count + 1);
        }
        List<SshKeyAdapter.Row> rows = new ArrayList<>();
        for (SshKeyRecord key : keys) {
            Integer count = usage.get(key.id);
            rows.add(new SshKeyAdapter.Row(key, count == null ? 0 : count));
        }
        adapter.setRows(rows);
        boolean empty = keys.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        keyList.setVisibility(empty ? View.GONE : View.VISIBLE);
        AppBar.setSubtitle(this, empty ? null : getString(R.string.ssh_key_count, keys.size()));
    }

    // -------------------------------------------------------------- generate

    private void showGenerateDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_generate_key, null);
        final EditText labelInput = (EditText) content.findViewById(R.id.edit_key_label);
        labelInput.setText(defaultKeyLabel());
        final RadioGroup bitsGroup = (RadioGroup) content.findViewById(R.id.rsa_bits_group);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.generate_key_title)
                .setView(bounded(content, 0.0f))
                .setPositiveButton(R.string.ssh_key_generate, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                String label = labelInput.getText().toString().trim();
                if (label.isEmpty()) {
                    labelInput.setError(getString(R.string.key_label_required));
                    labelInput.requestFocus();
                    return;
                }
                int bits = selectedRsaBits(bitsGroup);
                dialog.setCancelable(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.generate_in_progress);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                generateKey(dialog, label, bits);
            }
        });
    }

    private void generateKey(final AlertDialog dialog, final String label, final int bits) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SshKeyRecord key = SshKeyManager.generate(label, bits);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            try {
                                store.upsertSshKey(key);
                            } catch (IOException error) {
                                dialog.dismiss();
                                refreshKeys();
                                showError(getString(R.string.generate_failed,
                                        UiErrors.describeRaw(SshKeysActivity.this, error.getMessage())));
                                return;
                            }
                            dialog.dismiss();
                            refreshKeys();
                            Toast.makeText(SshKeysActivity.this,
                                    getString(R.string.key_created, label), Toast.LENGTH_SHORT).show();
                            showPublicKeyDialog(key);
                        }
                    });
                } catch (final AppError error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            restoreDialogButtons(dialog, R.string.ssh_key_generate);
                            showError(getString(R.string.generate_failed,
                                    UiErrors.describe(SshKeysActivity.this, error.kind, error.getMessage())));
                        }
                    });
                } catch (final RuntimeException error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            restoreDialogButtons(dialog, R.string.ssh_key_generate);
                            showError(getString(R.string.generate_failed,
                                    UiErrors.describe(SshKeysActivity.this, ErrorKind.INTERNAL,
                                            error.getMessage())));
                        }
                    });
                }
            }
        }, "ssh-key-generate").start();
    }

    private void restoreDialogButtons(AlertDialog dialog, int positiveTextRes) {
        if (dialog == null || !dialog.isShowing()) return;
        dialog.setCancelable(true);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setEnabled(true);
            positive.setText(positiveTextRes);
        }
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null) negative.setEnabled(true);
    }

    private int selectedRsaBits(RadioGroup group) {
        int checked = group == null ? View.NO_ID : group.getCheckedRadioButtonId();
        if (checked == R.id.rsa_bits_2048) return 2048;
        if (checked == R.id.rsa_bits_4096) return 4096;
        return SshKeyManager.DEFAULT_RSA_BITS;
    }

    // ---------------------------------------------------------------- import

    private void showImportDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_import_key, null);
        importLabelField = (EditText) content.findViewById(R.id.edit_import_label);
        importLabelField.setText(defaultKeyLabel());
        importPrivateField = (EditText) content.findViewById(R.id.edit_import_private_key);
        importPassphraseField = (EditText) content.findViewById(R.id.edit_import_passphrase);
        importPublicField = (EditText) content.findViewById(R.id.edit_import_public_key);
        importStatus = (TextView) content.findViewById(R.id.text_import_status);
        content.findViewById(R.id.btn_pick_private_file).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                pickPrivateKeyFile();
            }
        });

        importDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.import_key_title)
                .setView(bounded(content, IMPORT_DIALOG_HEIGHT_RATIO))
                .setPositiveButton(R.string.import_key_action, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        importDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                clearImportDialogRefs();
            }
        });
        importDialog.show();
        importDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                attemptImport(importDialog);
            }
        });
    }

    private void clearImportDialogRefs() {
        importDialog = null;
        importLabelField = null;
        importPrivateField = null;
        importPassphraseField = null;
        importPublicField = null;
        importStatus = null;
    }

    private void attemptImport(final AlertDialog dialog) {
        if (importLabelField == null || importPrivateField == null) return;
        String label = importLabelField.getText().toString().trim();
        if (label.isEmpty()) {
            importLabelField.setError(getString(R.string.key_label_required));
            importLabelField.requestFocus();
            return;
        }
        final String privateText = importPrivateField.getText().toString();
        if (privateText.trim().isEmpty()) {
            setImportStatus(getString(R.string.import_private_key_required), true);
            return;
        }
        if (!SshKeyManager.looksLikePrivateKey(privateText)) {
            setImportStatus(getString(R.string.import_private_key_format), true);
            return;
        }
        final String passphrase = importPassphraseField == null
                ? "" : importPassphraseField.getText().toString();
        final String publicLine = importPublicField == null
                ? "" : importPublicField.getText().toString().trim();

        setImportStatus(getString(R.string.import_in_progress), false);
        dialog.setCancelable(false);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setEnabled(false);
            positive.setText(R.string.import_in_progress);
        }
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null) negative.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SshKeyRecord key = SshKeyManager.importKeyPair(
                            label, privateText, passphrase, publicLine);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            try {
                                store.upsertSshKey(key);
                            } catch (IOException error) {
                                restoreDialogButtons(dialog, R.string.import_key_action);
                                setImportStatus(getString(R.string.import_key_failed,
                                        UiErrors.scrubExtra(SshKeysActivity.this, error.getMessage(),
                                                privateText, passphrase)), true);
                                return;
                            }
                            dialog.dismiss();
                            refreshKeys();
                            Toast.makeText(SshKeysActivity.this,
                                    getString(R.string.key_imported, label), Toast.LENGTH_SHORT).show();
                            showPublicKeyDialog(key);
                        }
                    });
                } catch (final AppError error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            restoreDialogButtons(dialog, R.string.import_key_action);
                            setImportStatus(UiErrors.scrubExtra(SshKeysActivity.this,
                                            UiErrors.describe(SshKeysActivity.this, error.kind, error.getMessage()),
                                            privateText, passphrase), true);
                        }
                    });
                } catch (final RuntimeException error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            restoreDialogButtons(dialog, R.string.import_key_action);
                            setImportStatus(UiErrors.scrubExtra(SshKeysActivity.this,
                                            UiErrors.describe(SshKeysActivity.this, ErrorKind.INTERNAL,
                                                    error.getMessage()),
                                            privateText, passphrase), true);
                        }
                    });
                }
            }
        }, "ssh-key-import").start();
    }

    private void setImportStatus(String text, boolean error) {
        if (importStatus == null) return;
        importStatus.setText(text);
        importStatus.setTextColor(getColor(error ? R.color.status_error : R.color.text_secondary));
    }

    private void pickPrivateKeyFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Private key files frequently have no extension, so accept everything
        // and narrow by MIME type; the 1 MiB cap lives in FileTextReader.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/*", "application/octet-stream", "application/x-pem-file", "application/pgp-keys"
        });
        try {
            startActivityForResult(intent, REQUEST_PICK_PRIVATE_KEY);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.file_picker_missing, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_PRIVATE_KEY || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || importPrivateField == null) return;
        try {
            String text = FileTextReader.read(getContentResolver(), uri);
            importPrivateField.setText(text);
            importPrivateField.setSelection(importPrivateField.getText().length());
            setImportStatus(getString(R.string.key_file_loaded,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length), false);
        } catch (FileTextReader.TooLargeException error) {
            Toast.makeText(this, R.string.key_file_too_large, Toast.LENGTH_SHORT).show();
        } catch (IOException error) {
            Toast.makeText(this,
                    getString(R.string.key_file_failed, UiErrors.describeRaw(this, error.getMessage())),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------- public key UI

    private void showPublicKeyDialog(final SshKeyRecord key) {
        View content = getLayoutInflater().inflate(R.layout.dialog_show_public_key, null);
        TextView body = (TextView) content.findViewById(R.id.text_public_key_body);
        body.setText(key.publicKey);
        new AlertDialog.Builder(this)
                .setTitle(R.string.public_key_title)
                .setView(bounded(content, PUBLIC_KEY_DIALOG_HEIGHT_RATIO))
                .setPositiveButton(R.string.copy_public_key, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(getString(R.string.public_key_title), key.publicKey, false);
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    // --------------------------------------------------------------- detail

    private void showDetailDialog(String keyId) {
        final SshKeyRecord key = store.findSshKey(keyId);
        if (key == null) {
            Toast.makeText(this, R.string.ssh_key_missing, Toast.LENGTH_SHORT).show();
            refreshKeys();
            return;
        }
        final List<String> usingHosts = new ArrayList<>();
        for (HostConfig host : store.list()) {
            if (keyId.equals(host.keyId)) usingHosts.add(host.alias);
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_ssh_key_detail, null);
        TextView meta = (TextView) content.findViewById(R.id.text_key_meta);
        meta.setText(getString(R.string.key_detail_meta,
                key.keyType,
                key.fingerprint,
                UiKeys.formatCreatedAt(key.createdAt),
                UiKeys.sourceLabel(this, key.source)));
        TextView publicView = (TextView) content.findViewById(R.id.text_public_key);
        publicView.setText(key.publicKey);
        TextView hostsView = (TextView) content.findViewById(R.id.text_key_hosts);
        if (usingHosts.isEmpty()) {
            hostsView.setText(R.string.ssh_key_usage_none);
        } else {
            StringBuilder joined = new StringBuilder();
            for (int index = 0; index < usingHosts.size(); index += 1) {
                if (index > 0) joined.append('\n');
                joined.append("• ").append(usingHosts.get(index));
            }
            hostsView.setText(joined);
        }
        final TextView privateHidden = (TextView) content.findViewById(R.id.text_private_hidden);
        final TextView privateView = (TextView) content.findViewById(R.id.text_private_key);
        final Button showButton = (Button) content.findViewById(R.id.btn_show_private_key);
        final boolean[] privateShown = new boolean[]{false};

        final AlertDialog detail = new AlertDialog.Builder(this)
                .setTitle(key.label)
                .setView(bounded(content, DETAIL_DIALOG_HEIGHT_RATIO))
                .setPositiveButton(R.string.close, null)
                .create();
        detail.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                // Drop the plaintext private key from the (now dead) dialog view.
                privateView.setText("");
            }
        });

        showButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                if (privateShown[0]) {
                    privateShown[0] = false;
                    privateView.setText("");
                    privateView.setVisibility(View.GONE);
                    privateHidden.setVisibility(View.VISIBLE);
                    showButton.setText(R.string.show_private_key);
                    return;
                }
                new AlertDialog.Builder(SshKeysActivity.this)
                        .setTitle(R.string.show_private_confirm_title)
                        .setMessage(R.string.show_private_confirm_message)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                privateShown[0] = true;
                                // Only now is the plaintext put into a view.
                                privateView.setText(key.privateKey);
                                privateView.setVisibility(View.VISIBLE);
                                privateHidden.setVisibility(View.GONE);
                                showButton.setText(R.string.hide_private_key);
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });
        content.findViewById(R.id.btn_copy_public_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                copyToClipboard(getString(R.string.public_key_title), key.publicKey, false);
            }
        });
        content.findViewById(R.id.btn_copy_private_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                new AlertDialog.Builder(SshKeysActivity.this)
                        .setTitle(R.string.copy_private_warning_title)
                        .setMessage(R.string.copy_private_warning_message)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                copyToClipboard(getString(R.string.copy_private_key), key.privateKey, true);
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });
        content.findViewById(R.id.btn_rename_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                showRenameDialog(key, detail);
            }
        });
        content.findViewById(R.id.btn_delete_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                confirmDeleteKey(key, detail);
            }
        });
        detail.show();
    }

    private void showRenameDialog(final SshKeyRecord key, final AlertDialog detailDialog) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setText(key.label);
        input.setSelection(input.getText().length());
        int padding = dp(16);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(padding, dp(8), padding, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_key_title)
                .setView(wrapper)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String label = input.getText().toString().trim();
                        if (label.isEmpty()) {
                            Toast.makeText(SshKeysActivity.this,
                                    R.string.key_label_required, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        SshKeyRecord renamed = key.copy();
                        renamed.label = label;
                        try {
                            store.upsertSshKey(renamed);
                        } catch (IOException error) {
                            Toast.makeText(SshKeysActivity.this,
                                    getString(R.string.key_save_failed,
                                            UiErrors.describeRaw(SshKeysActivity.this, error.getMessage())),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        detailDialog.dismiss();
                        refreshKeys();
                        Toast.makeText(SshKeysActivity.this,
                                getString(R.string.rename_key_done, label), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDeleteKey(final SshKeyRecord key, final AlertDialog detailDialog) {
        int usageCount = store.countHostsUsingKey(key.id);
        StringBuilder message = new StringBuilder(
                getString(R.string.delete_key_confirm_message, key.label));
        if (usageCount > 0) {
            message.append('\n').append('\n')
                    .append(getString(R.string.delete_key_used_warning, usageCount));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_key_title)
                .setMessage(message)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            boolean removed = store.removeSshKey(key.id);
                            detailDialog.dismiss();
                            refreshKeys();
                            Toast.makeText(SshKeysActivity.this,
                                    removed ? getString(R.string.key_deleted, key.label)
                                            : getString(R.string.ssh_key_missing),
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException error) {
                            Toast.makeText(SshKeysActivity.this,
                                    getString(R.string.key_delete_failed,
                                            UiErrors.describeRaw(SshKeysActivity.this, error.getMessage())),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------- utilities

    private String defaultKeyLabel() {
        Set<String> used = new HashSet<>();
        for (SshKeyRecord key : store.listSshKeys()) {
            if (key.label != null) used.add(key.label);
        }
        int number = 1;
        while (used.contains("app-key-" + number)) number += 1;
        return "app-key-" + number;
    }

    private void copyToClipboard(String label, String text, boolean sensitive) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, R.string.clipboard_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = ClipData.newPlainText(label, text);
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            // API 33+: tell the system not to show a clipboard preview.
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        try {
            manager.setPrimaryClip(clip);
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.clipboard_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private View bounded(View content, float heightRatio) {
        if (heightRatio <= 0f) return content;
        int height = Math.round(getResources().getDisplayMetrics().heightPixels * heightRatio);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        return content;
    }

    private void showError(String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.ssh_keys_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
