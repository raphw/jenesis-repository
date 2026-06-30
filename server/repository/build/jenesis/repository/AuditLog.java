package build.jenesis.repository;

import build.jenesis.repository.store.ArtifactStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A durable, queryable audit trail of security-relevant changes, kept in the same store as the credentials it
 * records. Each event - who ({@code actor}, a credential hash or a named source), what ({@code action}) and on what
 * ({@code target}) - is written as its own small object under {@code audit/<tenant>/<day>/}, append-only, so a
 * concurrent or replicated deployment never contends on a shared log. {@link #query} reads a tenant's events back,
 * filtered by time range and action and newest-first, for a console to show or an endpoint to export. Recording is
 * synchronous but best-effort: a failed write is dropped rather than failing the operation it audits. A disabled log
 * records nothing.
 */
public final class AuditLog {

    /** One audit event: when it happened, who acted, the action and the target it acted on. */
    public record Event(Instant at, String actor, String action, String target) {
    }

    private final ArtifactStore store;
    private final boolean enabled;
    private final AtomicLong sequence = new AtomicLong();

    public AuditLog(ArtifactStore store, boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Record an event for {@code tenant}; a no-op when disabled, and best-effort (a failed write is dropped). */
    public void record(String tenant, String actor, String action, String target) {
        if (!enabled || store == null) {
            return;
        }
        Instant now = Instant.now();
        Properties event = new Properties();
        event.setProperty("at", now.toString());
        event.setProperty("actor", actor == null ? "" : actor);
        event.setProperty("action", action == null ? "" : action);
        event.setProperty("target", target == null ? "" : target);
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        String path = "audit/" + tenant + "/" + day + "/" + now.toEpochMilli() + "-" + sequence.incrementAndGet();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            event.store(bytes, null);
            store.write(path, new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IOException e) {
            // best-effort: an audit write must not fail the audited operation
        }
    }

    /** A tenant's events, newest first, optionally bounded by {@code from}/{@code to} (inclusive) and a single
     *  {@code action}; any of the filters may be {@code null}. */
    public List<Event> query(String tenant, Instant from, Instant to, String action) throws IOException {
        if (store == null) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        for (String day : store.list("audit/" + tenant)) {
            for (String name : store.list("audit/" + tenant + "/" + day)) {
                Properties event = read("audit/" + tenant + "/" + day + "/" + name);
                if (event == null) {
                    continue;
                }
                Instant at = Instant.parse(event.getProperty("at"));
                if (from != null && at.isBefore(from)) {
                    continue;
                }
                if (to != null && at.isAfter(to)) {
                    continue;
                }
                if (action != null && !action.isBlank() && !action.equals(event.getProperty("action"))) {
                    continue;
                }
                events.add(new Event(at, event.getProperty("actor"), event.getProperty("action"),
                        event.getProperty("target")));
            }
        }
        events.sort(Comparator.comparing(Event::at).reversed());
        return events;
    }

    private Properties read(String path) throws IOException {
        Optional<ArtifactStore.Versioned> object = store.readVersioned(path);
        if (object.isEmpty()) {
            return null;
        }
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(object.get().content()));
        return properties;
    }
}
