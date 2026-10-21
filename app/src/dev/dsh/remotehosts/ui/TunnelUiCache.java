package dev.dsh.remotehosts.ui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Process-local view of tunnel state, used only to paint the status chips.
 *
 * The foreground service is the source of truth: it broadcasts every state
 * change and exposes {@code TunnelService.openTunnelsSnapshot()}. This cache
 * exists because a broadcast can be missed while the activity is paused (the
 * receiver is unregistered then); on every resume the open tunnels are
 * re-synchronised from the service snapshot.
 *
 * It is deliberately UI-only: the tunnel engine and the service never read it.
 */
final class TunnelUiCache {

    static final int STATE_NONE = 0;
    static final int STATE_CONNECTING = 1;
    static final int STATE_OPEN = 2;
    static final int STATE_FAILED = 3;

    private static final Map<String, Integer> STATES = new HashMap<>();
    private static final Map<String, Integer> PORTS = new HashMap<>();

    private TunnelUiCache() {
    }

    static synchronized void set(String alias, int state, int localPort) {
        if (alias == null) return;
        if (state == STATE_NONE) {
            forget(alias);
            return;
        }
        STATES.put(alias, state);
        PORTS.put(alias, localPort > 0 ? localPort : 0);
    }

    static synchronized int state(String alias) {
        Integer value = alias == null ? null : STATES.get(alias);
        return value == null ? STATE_NONE : value;
    }

    /** Local loopback port for an OPEN tunnel, or 0. */
    static synchronized int localPort(String alias) {
        Integer value = alias == null ? null : PORTS.get(alias);
        return value == null ? 0 : value;
    }

    static synchronized void forget(String alias) {
        if (alias == null) return;
        STATES.remove(alias);
        PORTS.remove(alias);
    }

    static synchronized void clear() {
        STATES.clear();
        PORTS.clear();
    }

    static synchronized boolean hasActive() {
        for (Integer state : STATES.values()) {
            if (state != null && (state == STATE_OPEN || state == STATE_CONNECTING)) return true;
        }
        return false;
    }

    /**
     * Re-synchronise OPEN entries with the service snapshot (call on resume).
     * Positive entries in the snapshot become OPEN; OPEN entries that the
     * service no longer knows about are dropped. Locally known CONNECTING /
     * FAILED states are kept so a failure notice survives a short pause.
     */
    static synchronized void syncOpenFromService(Map<String, Integer> snapshot) {
        Map<String, Integer> safe = snapshot == null ? new HashMap<String, Integer>() : snapshot;
        Iterator<Map.Entry<String, Integer>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            if (entry.getValue() != null && entry.getValue() == STATE_OPEN && !safe.containsKey(entry.getKey())) {
                iterator.remove();
                PORTS.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, Integer> entry : safe.entrySet()) {
            if (entry.getKey() == null) continue;
            Integer port = entry.getValue();
            STATES.put(entry.getKey(), STATE_OPEN);
            PORTS.put(entry.getKey(), port == null ? 0 : port);
        }
    }
}
