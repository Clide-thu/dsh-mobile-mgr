package dev.dsh.remotehosts.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.SshKeyRecord;

/**
 * Rows for the SSH key list (docs/app-spec.md §7.4). Never renders the private
 * key or passphrase: only label / type / fingerprint / usage / creation time.
 */
final class SshKeyAdapter extends BaseAdapter {

    /** One row: key snapshot + number of hosts that reference it. */
    static final class Row {
        final SshKeyRecord key;
        final int usageCount;

        Row(SshKeyRecord key, int usageCount) {
            this.key = key;
            this.usageCount = usageCount;
        }
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();

    SshKeyAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    void setRows(List<Row> newRows) {
        rows.clear();
        if (newRows != null) rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Row getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        ViewHolder holder;
        if (view == null) {
            view = inflater.inflate(R.layout.item_ssh_key, parent, false);
            holder = new ViewHolder(view);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }
        Row row = rows.get(position);
        SshKeyRecord key = row.key;

        holder.label.setText(key.label == null || key.label.isEmpty() ? "?" : key.label);
        holder.meta.setText(key.keyType + " · " + UiKeys.shortFingerprint(key.fingerprint));
        holder.created.setText(context.getString(
                R.string.ssh_key_created, UiKeys.formatCreatedAt(key.createdAt)));
        if (row.usageCount > 0) {
            holder.usage.setText(context.getString(R.string.ssh_key_usage, row.usageCount));
            holder.usage.setBackgroundResource(R.drawable.bg_chip_ok);
            holder.usage.setTextColor(context.getColor(R.color.status_ok));
        } else {
            holder.usage.setText(R.string.ssh_key_usage_none);
            holder.usage.setBackgroundResource(R.drawable.bg_chip_neutral);
            holder.usage.setTextColor(context.getColor(R.color.chip_neutral_text));
        }
        return view;
    }

    private static final class ViewHolder {
        final TextView label;
        final TextView usage;
        final TextView meta;
        final TextView created;

        ViewHolder(View view) {
            label = (TextView) view.findViewById(R.id.key_label);
            usage = (TextView) view.findViewById(R.id.key_usage);
            meta = (TextView) view.findViewById(R.id.key_meta);
            created = (TextView) view.findViewById(R.id.key_created);
        }
    }
}
