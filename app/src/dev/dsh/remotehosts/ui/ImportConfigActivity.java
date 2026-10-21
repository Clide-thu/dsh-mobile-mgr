package dev.dsh.remotehosts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.HostStore;
import dev.dsh.remotehosts.core.SshConfigParser;

/**
 * Paste or pick an OpenSSH {@code config} file, preview what would be imported,
 * then merge it into the encrypted store (docs/app-spec.md §7.3).
 *
 * Merge rule: an alias that already exists keeps its saved auth material and
 * DSH credential; only connection fields (host name / user / port / jump) are
 * refreshed from the config. New aliases are added with no password yet.
 */
public class ImportConfigActivity extends Activity {

    private static final int REQUEST_PICK_CONFIG = 3201;
    private static final int PREVIEW_LIMIT = 20;

    private HostStore store;
    private EditText editConfigText;
    private TextView textFileName;
    private TextView textSummary;
    private TextView textWarnings;
    private TextView textIdentityWarning;
    private TextView textPreview;
    private LinearLayout groupResult;
    private Button btnParse;
    private Button btnApply;

    private List<HostConfig> parsedHosts = new ArrayList<>();
    private List<String> parsedWarnings = new ArrayList<>();
    private int skippedWildcards;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_config);
        store = HostStore.get(this);

        AppBar.bind(this);
        AppBar.setTitle(this, R.string.import_title);
        AppBar.setBackVisible(this, true);
        UiInsets.apply(this);

        editConfigText = (EditText) findViewById(R.id.edit_config_text);
        textFileName = (TextView) findViewById(R.id.text_file_name);
        textSummary = (TextView) findViewById(R.id.text_import_summary);
        textWarnings = (TextView) findViewById(R.id.text_import_warnings);
        textIdentityWarning = (TextView) findViewById(R.id.text_identity_warning);
        textPreview = (TextView) findViewById(R.id.text_import_preview);
        groupResult = (LinearLayout) findViewById(R.id.group_result);
        btnParse = (Button) findViewById(R.id.btn_parse);
        btnApply = (Button) findViewById(R.id.btn_apply);

        findViewById(R.id.btn_pick_file).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                pickConfigFile();
            }
        });
        btnParse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                parseNow();
            }
        });
        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                confirmApply();
            }
        });
    }

    // ---------------------------------------------------------------- parse

    private void parseNow() {
        String text = editConfigText.getText().toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, R.string.import_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        SshConfigParser.ParseResult result;
        try {
            result = SshConfigParser.parse(text);
        } catch (RuntimeException error) {
            showMessageDialog(getString(R.string.import_title),
                    getString(R.string.import_failed, UiErrors.describeRaw(this, error.getMessage())));
            return;
        }
        parsedHosts = result == null || result.hosts == null ? new ArrayList<HostConfig>() : new ArrayList<>(result.hosts);
        parsedWarnings = result == null || result.warnings == null ? new ArrayList<String>() : new ArrayList<>(result.warnings);
        skippedWildcards = countWildcardPatterns(text);
        showParseResult();
        btnParse.setText(R.string.import_reparse);
    }

    private void showParseResult() {
        groupResult.setVisibility(View.VISIBLE);

        StringBuilder summary = new StringBuilder();
        if (parsedHosts.isEmpty()) {
            summary.append(getString(R.string.import_nothing));
        } else {
            summary.append(getString(R.string.import_result, parsedHosts.size(), parsedWarnings.size()));
            summary.append('\n').append(getString(R.string.import_skipped_wildcards, skippedWildcards));
        }
        textSummary.setText(summary);

        if (parsedHosts.isEmpty()) {
            textWarnings.setText(R.string.import_nothing_hint);
        } else if (parsedWarnings.isEmpty()) {
            textWarnings.setText("");
        } else {
            StringBuilder warnings = new StringBuilder(getString(R.string.import_warnings_label));
            for (String warning : parsedWarnings) {
                warnings.append('\n').append("• ").append(UiErrors.describeRaw(this, warning));
            }
            textWarnings.setText(warnings);
        }

        boolean hasIdentityFiles = false;
        for (HostConfig host : parsedHosts) {
            if (host.identityFiles != null && !host.identityFiles.isEmpty()) {
                hasIdentityFiles = true;
                break;
            }
        }
        textIdentityWarning.setVisibility(hasIdentityFiles ? View.VISIBLE : View.GONE);

        if (parsedHosts.isEmpty()) {
            textPreview.setText("");
        } else {
            StringBuilder preview = new StringBuilder(getString(R.string.import_preview_label, parsedHosts.size()));
            int shown = Math.min(PREVIEW_LIMIT, parsedHosts.size());
            for (int index = 0; index < shown; index += 1) {
                HostConfig host = parsedHosts.get(index);
                preview.append('\n').append(host.alias).append("  ").append(host.sshEndpoint());
                if (host.proxyJump != null && !host.proxyJump.isEmpty()) {
                    preview.append("  → ").append(host.proxyJump);
                }
            }
            int remaining = parsedHosts.size() - shown;
            if (remaining > 0) {
                preview.append('\n').append(getString(R.string.import_preview_more, remaining));
            }
            textPreview.setText(preview);
        }

        btnApply.setText(getString(R.string.import_apply, parsedHosts.size()));
        btnApply.setEnabled(!parsedHosts.isEmpty());
    }

    /**
     * The parser reports warnings but not how many wildcard patterns it skipped,
     * so count them from the raw text for the "跳过 M 个通配 pattern" line.
     */
    private int countWildcardPatterns(String text) {
        int count = 0;
        String[] lines = text.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String lower = line.toLowerCase(Locale.US);
            String rest = null;
            if (lower.startsWith("host ") || lower.startsWith("host\t")) {
                rest = line.substring(5);
            } else if (lower.startsWith("host=")) {
                rest = line.substring(5);
            }
            if (rest == null) continue;
            String[] patterns = rest.trim().split("\\s+");
            for (String pattern : patterns) {
                if (pattern.isEmpty()) continue;
                if (pattern.startsWith("!") || pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) {
                    count += 1;
                }
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- apply

    private void confirmApply() {
        if (parsedHosts.isEmpty()) {
            Toast.makeText(this, R.string.import_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        int newCount = 0;
        int updateCount = 0;
        for (HostConfig parsed : parsedHosts) {
            if (store.find(parsed.alias) != null) {
                updateCount += 1;
            } else {
                newCount += 1;
            }
        }
        final int finalNewCount = newCount;
        final int finalUpdateCount = updateCount;
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_confirm_title)
                .setMessage(getString(R.string.import_confirm_message,
                        parsedHosts.size(), newCount, updateCount))
                .setPositiveButton(R.string.import_confirm_apply, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyImport(finalNewCount, finalUpdateCount);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applyImport(int expectedNewCount, int expectedUpdateCount) {
        int added = 0;
        int updated = 0;
        try {
            for (HostConfig parsed : parsedHosts) {
                HostConfig existing = store.find(parsed.alias);
                HostConfig merged;
                if (existing == null) {
                    merged = parsed;
                    added += 1;
                } else {
                    merged = existing.copy();
                    // Refresh connection fields; keep auth material + DSH credential.
                    if (parsed.hostName != null && !parsed.hostName.isEmpty()) {
                        merged.hostName = parsed.hostName;
                    }
                    if (parsed.user != null && !parsed.user.isEmpty()) {
                        merged.user = parsed.user;
                    }
                    merged.port = parsed.port;
                    merged.proxyJump = parsed.proxyJump == null ? "" : parsed.proxyJump;
                    merged.identityFiles = new ArrayList<>(
                            parsed.identityFiles == null ? new ArrayList<String>() : parsed.identityFiles);
                    if (parsed.importedFrom != null && !parsed.importedFrom.isEmpty()) {
                        merged.importedFrom = parsed.importedFrom;
                    }
                    updated += 1;
                }
                store.upsert(merged);
            }
        } catch (IOException error) {
            showMessageDialog(getString(R.string.import_title),
                    getString(R.string.import_apply_failed, UiErrors.describeRaw(this, error.getMessage())));
            return;
        }
        Toast.makeText(this,
                getString(R.string.import_apply_done, added, updated), Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    // ------------------------------------------------------------ file picker

    private void pickConfigFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // A config file often has no extension, so accept everything and narrow
        // with EXTRA_MIME_TYPES: documents apps list text/plain under text/*,
        // extension-less files usually surface as application/octet-stream.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_PICK_CONFIG);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.file_picker_missing, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_CONFIG || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String content = FileTextReader.read(getContentResolver(), uri);
            editConfigText.setText(content);
            textFileName.setText(getString(R.string.import_file_loaded,
                    displayName(uri), content.getBytes(StandardCharsets.UTF_8).length));
            parseNow();
        } catch (FileTextReader.TooLargeException error) {
            Toast.makeText(this, R.string.import_file_too_large, Toast.LENGTH_SHORT).show();
        } catch (IOException error) {
            Toast.makeText(this,
                    getString(R.string.import_file_failed, UiErrors.describeRaw(this, error.getMessage())),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String displayName(Uri uri) {
        try {
            Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (column >= 0) {
                            String name = cursor.getString(column);
                            if (name != null && !name.isEmpty()) return name;
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to the URI's last segment.
        }
        String last = uri.getLastPathSegment();
        return last == null ? uri.toString() : last;
    }

    // -------------------------------------------------------------- helpers

    private void showMessageDialog(String title, String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
