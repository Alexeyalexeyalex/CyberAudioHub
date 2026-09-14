package com.cyberaudio.hub;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Durable account state shared by every Api instance. No passwords are stored. */
final class AccountSession {
    interface Persistence {
        String get(String key, String fallback);
        void put(Map<String, String> changes);
    }
    static final class Snapshot {
        final String server, cookie, revision;
        Snapshot(String server, String cookie, String revision) {
            this.server = server; this.cookie = cookie; this.revision = revision;
        }
    }
    private static final Object LOCK = new Object();
    private final Persistence storage;
    AccountSession(Persistence storage) { this.storage = storage; }

    Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(ServerAddress.normalize(storage.get("server", "")),
                    storage.get("cookie", ""), storage.get("account_revision", "legacy"));
        }
    }
    String cachedProfile() { synchronized (LOCK) { return storage.get("profile", ""); } }

    /** A cookie refresh is not an account change. Never use this after logout. */
    boolean sameSignedInAccount(Snapshot request) {
        synchronized (LOCK) {
            Snapshot current = snapshot();
            return !request.cookie.isEmpty() && !current.cookie.isEmpty()
                    && Objects.equals(current.server, request.server)
                    && Objects.equals(current.revision, request.revision);
        }
    }

    boolean setServer(String raw) {
        String server = ServerAddress.normalize(raw);
        synchronized (LOCK) {
            boolean changed = !server.equals(snapshot().server);
            Map<String, String> changes = new HashMap<>();
            changes.put("server", server);
            if (changed) { changes.put("cookie", null); changes.put("profile", null); newRevision(changes); }
            storage.put(changes);
            return changed;
        }
    }
    void forget(boolean keepProfile) {
        synchronized (LOCK) {
            Map<String, String> changes = new HashMap<>();
            changes.put("cookie", null);
            if (!keepProfile) changes.put("profile", null);
            newRevision(changes); storage.put(changes);
        }
    }
    /** Keep the durable account until a complete new login has arrived. */
    Snapshot beginLogin() {
        synchronized (LOCK) {
            Map<String, String> changes = new HashMap<>();
            newRevision(changes); storage.put(changes);
            return snapshot();
        }
    }

    boolean acceptLogin(Snapshot request, String cookie, String profile) {
        if (cookie == null || cookie.isEmpty() || profile == null) return false;
        synchronized (LOCK) {
            Snapshot current = snapshot();
            if (!Objects.equals(current.server, request.server)
                    || !Objects.equals(current.revision, request.revision)) return false;
            // Refreshes of the previous account may finish during login. They
            // must neither cancel this login nor overwrite it after this commit.
            Map<String, String> changes = new HashMap<>();
            changes.put("cookie", cookie); changes.put("profile", profile);
            newRevision(changes); storage.put(changes);
            return true;
        }
    }

    /** Reject old-server, old-login and out-of-order cookie responses, including logout. */
    boolean accept(Snapshot request, String cookie, boolean hasProfile, String profile) {
        synchronized (LOCK) {
            Snapshot current = snapshot();
            if (!Objects.equals(current.server, request.server) || !Objects.equals(current.revision, request.revision)
                    || !Objects.equals(current.cookie, request.cookie)) return false;
            Map<String, String> changes = new HashMap<>();
            if (cookie != null) changes.put("cookie", cookie.isEmpty() ? null : cookie);
            if (hasProfile) {
                if (profile != null) changes.put("profile", profile);
                // Cookie comparison rejects other replies for this expired
                // session. Do not cancel a credential login already in flight;
                // explicit logout/server changes still invalidate its revision.
                else changes.put("cookie", null);
            }
            if (!changes.isEmpty()) storage.put(changes);
            return true;
        }
    }
    private static void newRevision(Map<String, String> changes) {
        changes.put("account_revision", java.util.UUID.randomUUID().toString());
    }
}
