package dev.dsh.remotehosts.ui;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.CredentialUrl;
import dev.dsh.remotehosts.core.HostConfig;

/**
 * Card-style rows for the host list (docs/app-spec.md §7.1).
 *
 * Security: the second/third line never show the DSH token in clear text; the
 * credential URL editor is the only place a token is ever typed, and the list
 * only ever shows {@link CredentialUrl#maskToken(String)}.
 */
final class HostAdapter extends BaseAdapter {

    interface Listener {
        void onOpen(HostConfig host);

        void onEdit(HostConfig host);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Listener listener;
    private final List<HostConfig> hosts = new ArrayList<>();

    HostAdapter(Context context, Listener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    void setHosts(List<HostConfig> newHosts) {
        hosts.clear();
        if (newHosts != null) hosts.addAll(newHosts);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return hosts.size();
    }

    @Override
    public HostConfig getItem(int position) {
        return hosts.get(position);
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
            view = inflater.inflate(R.layout.item_host, parent, false);
            holder = new ViewHolder(view);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }
        final HostConfig host = hosts.get(position);
        bind(holder, host);
        holder.open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                listener.onOpen(host);
            }
        });
        holder.edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                listener.onEdit(host);
            }
        });
        return view;
    }

    private void bind(ViewHolder holder, HostConfig host) {
        holder.alias.setText(host.alias == null || host.alias.isEmpty() ? "?" : host.alias);

        if (host.accessMode == HostConfig.AccessMode.DIRECT_URL) {
            holder.endpoint.setText(context.getString(R.string.direct_url_mode));
        } else {
            String endpoint = host.sshEndpoint();
            String proxyJump = host.proxyJump == null ? "" : host.proxyJump.trim();
            if (!proxyJump.isEmpty()) {
                endpoint = endpoint + context.getString(R.string.via_proxy_jump, proxyJump);
            }
            holder.endpoint.setText(endpoint);
        }

        bindCredentialChip(holder.credChip, host);

        if (host.accessMode == HostConfig.AccessMode.DIRECT_URL) {
            String target = authorityOf(host.directUrl);
            holder.credential.setText(target.isEmpty()
                    ? context.getString(R.string.row_direct_url_missing)
                    : context.getString(R.string.row_direct_url, target));
        } else if (host.hasCredential()) {
            holder.credential.setText(context.getString(
                    R.string.row_credential_tunnel,
                    host.remotePort,
                    CredentialUrl.maskToken(host.token)));
        } else {
            holder.credential.setText(R.string.row_credential_missing);
        }

        bindTunnelChip(holder.tunnelChip, host.alias);
    }

    /** Credential / SSH-auth completeness chip (docs/app-spec.md §7.1). */
    private void bindCredentialChip(TextView chip, HostConfig host) {
        boolean direct = host.accessMode == HostConfig.AccessMode.DIRECT_URL;
        boolean sshReady = direct || host.hasSshIdentity();
        boolean credentialReady = host.hasCredential();
        int textRes;
        if (!credentialReady) {
            textRes = R.string.status_no_credential;
            setChip(chip, textRes, R.drawable.bg_chip_warn, R.color.status_warn);
        } else if (!sshReady) {
            textRes = R.string.status_no_ssh_auth;
            setChip(chip, textRes, R.drawable.bg_chip_warn, R.color.status_warn);
        } else {
            textRes = R.string.status_configured;
            setChip(chip, textRes, R.drawable.bg_chip_ok, R.color.status_ok);
        }
        chip.setVisibility(View.VISIBLE);
        chip.setText(textRes);
    }

    /** Live tunnel chip: only shown when the service reports something for this host. */
    private void bindTunnelChip(TextView chip, String alias) {
        int state = TunnelUiCache.state(alias);
        if (state == TunnelUiCache.STATE_NONE) {
            chip.setVisibility(View.GONE);
            return;
        }
        chip.setVisibility(View.VISIBLE);
        if (state == TunnelUiCache.STATE_OPEN) {
            int port = TunnelUiCache.localPort(alias);
            chip.setText(context.getString(R.string.status_connected, port));
            setChipColors(chip, R.drawable.bg_chip_ok, R.color.status_ok);
        } else if (state == TunnelUiCache.STATE_CONNECTING) {
            chip.setText(R.string.status_connecting);
            setChipColors(chip, R.drawable.bg_chip_warn, R.color.status_warn);
        } else {
            chip.setText(R.string.status_failed);
            setChipColors(chip, R.drawable.bg_chip_error, R.color.status_error);
        }
    }

    private void setChip(TextView chip, int textRes, int backgroundRes, int colorRes) {
        chip.setText(textRes);
        setChipColors(chip, backgroundRes, colorRes);
    }

    private void setChipColors(TextView chip, int backgroundRes, int colorRes) {
        chip.setBackgroundResource(backgroundRes);
        chip.setTextColor(context.getColor(colorRes));
    }

    /** {@code scheme://host:port} of a URL; never the query string (may carry secrets). */
    private String authorityOf(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return "";
            StringBuilder out = new StringBuilder();
            out.append(scheme).append("://").append(host);
            if (uri.getPort() > 0) out.append(':').append(uri.getPort());
            return out.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static final class ViewHolder {
        final TextView alias;
        final TextView endpoint;
        final TextView credential;
        final TextView credChip;
        final TextView tunnelChip;
        final Button open;
        final Button edit;

        ViewHolder(View view) {
            alias = (TextView) view.findViewById(R.id.host_alias);
            endpoint = (TextView) view.findViewById(R.id.host_endpoint);
            credential = (TextView) view.findViewById(R.id.host_credential);
            credChip = (TextView) view.findViewById(R.id.chip_credential);
            tunnelChip = (TextView) view.findViewById(R.id.chip_tunnel);
            open = (Button) view.findViewById(R.id.btn_open);
            edit = (Button) view.findViewById(R.id.btn_edit);
        }
    }
}
