package build.jenesis.repository.store;

import module java.base;

/**
 * A reusable incremental-derived-index primitive: the small change-feed a module builds a derived index on so its
 * steady state is O(&Delta;) - proportional to what changed - instead of O(N) over the whole coordinate set every
 * pass. This is the <b>dirty-set</b> form (the minimal one): each touched coordinate is recorded as a marker under a
 * {@code &hellip;/dirty/} store prefix; a sweep reads the marked coordinates ({@link #applySince}), the caller applies
 * each to its own derived index idempotently, and the applied markers are cleared ({@link #clear}) so the next sweep
 * sees only what changed since. A periodic {@link #compactThrough full reconcile} rebuilds the index from durable
 * truth and garbage-collects the feed, healing anything the events missed. Built on {@link ArtifactStore} and
 * {@code java.base} only - no ServiceLoader, no config, no server - so any module (the search index, a dependents
 * graph, a size roll-up) reuses it against whatever store it already holds.
 *
 * <h2>Why a dirty-set and not a journal</h2>
 * A dirty-set records only <em>which</em> coordinates changed, never an order, so there is nothing to sequence and
 * re-marking the same coordinate simply coalesces onto one marker (its key is a stable hash of the coordinate). That
 * is exactly enough for an <em>upsert-by-coordinate</em> index: applying "coordinate X changed" re-derives X's current
 * document from truth, so a duplicate or out-of-order mark is harmless. The richer form - a segmented, compactable
 * <b>append-only journal with a durable cursor</b> (the same shape as the forwarding / webhooks outbox: append events,
 * advance a cursor past the applied prefix, compact old segments) - is the documented next step for a consumer that
 * needs the actual event stream (deltas it cannot re-derive, strict per-coordinate ordering, or fan-out to several
 * cursors). It is deliberately <em>not</em> built here; ship the dirty-set first.
 *
 * <h2>The correctness discipline (the caller's half)</h2>
 * The primitive carries a monotonic {@code version} on every marker - the artifact's publish time, already stamped -
 * and the caller applies it under three rules the sweep is built around:
 * <ul>
 *   <li><b>Out-of-order guard.</b> Apply an event only if its {@link Entry#version version} &ge; the version already
 *       recorded for that coordinate in the index, so a stale marker (a slow event overtaken by a newer publish)
 *       never regresses a newer document. The applier returns {@code false} to skip such an entry; a skipped marker is
 *       left in the feed, not cleared. The feed itself also keeps only the newest version when a coordinate is
 *       re-marked ({@link #touched} / {@link #removed} coalesce to the higher version).</li>
 *   <li><b>Idempotent upsert-by-coordinate.</b> Applying a marker re-derives that one coordinate's document (an
 *       upsert, or a delete for a {@link Entry#removed removed} marker), so replaying a marker is a no-op - which is
 *       what makes the next rule safe.</li>
 *   <li><b>Crash-safe advance.</b> Clear a marker only <em>after</em> the derived index's new snapshot has committed
 *       ({@link #applySince} returns the applied entries; the caller commits its snapshot, then calls {@link #clear}).
 *       A crash between commit and clear only replays already-applied markers next sweep, absorbed by the idempotent
 *       upsert; a crash before commit leaves the markers, so nothing is lost.</li>
 * </ul>
 * Missed events - an import or a manual store edit that bypasses the write path and so never marks the coordinate -
 * are caught only by the {@link #compactThrough reconcile} backstop, which rebuilds from truth; keep it scheduled and
 * route the live write / delete paths through the observer that marks the feed. A derived index therefore lags a write
 * by at most one sweep interval - explicit, and acceptable for a search index.
 *
 * <h2>Concurrency</h2>
 * A single-writer sweep is assumed (the caller holds its own lease - e.g. the {@code search-index} lease). Markers are
 * written compare-and-set on the store's version token so concurrent {@link #touched} calls from the write path never
 * lose one another, and {@link #clear} deletes a marker only while its token is unchanged since it was read, so a
 * coordinate re-touched <em>during</em> the sweep survives to the next one rather than being cleared unapplied. The
 * one residue - a re-touch landing between {@code clear}'s token re-read and its delete - is healed by the reconcile,
 * the same reconcile-heals-partials model the store's batch writes and the inventory reconcile already rely on. This
 * type holds no mutable static state; a feed instance is a thin handle over the store and its prefix.
 */
public final class DirtyIndexFeed {

    /** How many compare-and-set attempts a marker write makes before giving up - a marker races only other writers
     *  touching the <em>same</em> coordinate, so contention is low and a small bound suffices. */
    private static final int MARK_ATTEMPTS = 8;

    private final ArtifactStore store;
    private final String dirtyPrefix;

    /** A feed over {@code store} whose markers live under {@code prefix + "/dirty/"} - the derived index picks a
     *  prefix in its own namespace (for the search index, {@code index/search}), so several feeds coexist in one
     *  store. */
    public DirtyIndexFeed(ArtifactStore store, String prefix) {
        this.store = Objects.requireNonNull(store, "store");
        String trimmed = strip(Objects.requireNonNull(prefix, "prefix"));
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a dirty-index feed needs a non-empty prefix");
        }
        this.dirtyPrefix = trimmed + "/dirty";
    }

    /** One touched coordinate the feed carries: the coordinate string (opaque to the feed - the derived index gives it
     *  meaning), the monotonic {@code version} stamped when it changed (the artifact's publish time), whether the
     *  change was a {@code removed} deletion rather than an upsert, and the store version {@code token} of the marker
     *  it was read from - the token {@link #clear} deletes against so a re-touch since the read survives. */
    public record Entry(String coordinate, long version, boolean removed, Object token) {
    }

    /** The caller's per-coordinate apply step, run once per pending marker by {@link #applySince}. It applies the
     *  entry to the derived index idempotently - an upsert-by-coordinate, or a delete when {@link Entry#removed} - and
     *  returns whether the entry was applied: {@code true} to have {@link #applySince} report it for clearing,
     *  {@code false} to skip it (the out-of-order guard: {@link Entry#version} is older than the version already
     *  indexed for the coordinate), leaving its marker in the feed rather than clearing it. */
    @FunctionalInterface
    public interface Applier {
        boolean apply(Entry entry) throws IOException;
    }

    /** Mark a coordinate upserted (published or updated) at {@code version}. Coalesces onto the coordinate's single
     *  marker, keeping the newest version, so repeated touches never grow the feed. */
    public void touched(String coordinate, long version) throws IOException {
        mark(coordinate, version, false);
    }

    /** Mark a coordinate removed at {@code version}, so the sweep applies a delete-by-coordinate. Coalesces exactly
     *  like {@link #touched}: the newest version's op wins, so a delete newer than a pending upsert supersedes it. */
    public void removed(String coordinate, long version) throws IOException {
        mark(coordinate, version, true);
    }

    private void mark(String coordinate, long version, boolean removed) throws IOException {
        Objects.requireNonNull(coordinate, "coordinate");
        String key = keyFor(coordinate);
        for (int attempt = 0; attempt < MARK_ATTEMPTS; attempt++) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            if (current.isPresent() && decode(current.get()).version() > version) {
                return; // a strictly newer change is already recorded; this stale mark adds nothing
            }
            Object expected = current.map(ArtifactStore.Versioned::token).orElse(null);
            if (store.writeVersioned(key, encode(coordinate, version, removed), expected)) {
                return;
            }
        }
        throw new IOException("could not record dirty marker for " + coordinate
                + " after " + MARK_ATTEMPTS + " attempts");
    }

    /** The coordinates currently marked dirty, each with its version, op and marker token. This is the O(&Delta;)
     *  read a sweep does instead of enumerating the whole coordinate set: it lists only the {@code dirty/} prefix, so
     *  its cost is the number of pending changes, independent of the index size. */
    public List<Entry> pending() throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String name : store.list(dirtyPrefix)) {
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(dirtyPrefix + "/" + name);
            if (marker.isPresent()) {
                entries.add(decode(marker.get()));
            }
        }
        return entries;
    }

    /** Read the dirty set and run {@code applier} over each entry, returning the entries the applier reported applied -
     *  which the caller {@link #clear}s only <em>after</em> committing the derived index's new snapshot (the crash-safe
     *  advance). Entries the applier skips (the out-of-order guard) are left in the feed for a later sweep. */
    public List<Entry> applySince(Applier applier) throws IOException {
        List<Entry> applied = new ArrayList<>();
        for (Entry entry : pending()) {
            if (applier.apply(entry)) {
                applied.add(entry);
            }
        }
        return applied;
    }

    /** Compact the feed by removing the markers just applied, each deleted only while its token is unchanged since it
     *  was read - so a coordinate re-touched during the sweep (a new token) survives to the next sweep instead of
     *  being cleared unapplied. Call this only after the derived index snapshot the entries were applied into has
     *  committed. */
    public void clear(Collection<Entry> applied) throws IOException {
        for (Entry entry : applied) {
            String key = keyFor(entry.coordinate());
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            if (current.isPresent() && Objects.equals(current.get().token(), entry.token())) {
                store.delete(key);
            }
        }
    }

    /** The reconcile backstop's feed garbage-collection: drop every marker recorded no later than {@code throughVersion}
     *  - the cutoff the reconcile captured before it rebuilt the index from truth, so a change marked <em>after</em>
     *  the rebuild started (and possibly not reflected in that rebuild) is kept for the next incremental sweep. A
     *  reconcile that rebuilds from durable truth heals whatever the feed missed, then calls this to bound the feed.
     *  Pass {@link Long#MAX_VALUE} to clear the whole feed. */
    public void compactThrough(long throughVersion) throws IOException {
        for (String name : store.list(dirtyPrefix)) {
            String key = dirtyPrefix + "/" + name;
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(key);
            if (marker.isPresent() && decode(marker.get()).version() <= throughVersion) {
                store.delete(key);
            }
        }
    }

    /** The store key a coordinate's single marker lives at: the SHA-256 hex of the coordinate under the {@code dirty/}
     *  prefix. A stable function of the coordinate, so re-marking coalesces onto one key; hashed so any coordinate
     *  string (with slashes, colons, whatever a format uses) becomes one traversal-free, fixed-length store segment. */
    private String keyFor(String coordinate) {
        return dirtyPrefix + "/" + sha256Hex(coordinate);
    }

    /** Marker body: a first line {@code "<version> <U|R>"} then the coordinate as the remainder, so a coordinate
     *  carrying any character (bar a newline, which no artifact coordinate does) round-trips verbatim. */
    private static byte[] encode(String coordinate, long version, boolean removed) {
        return (version + " " + (removed ? "R" : "U") + "\n" + coordinate).getBytes(StandardCharsets.UTF_8);
    }

    private static Entry decode(ArtifactStore.Versioned marker) {
        String body = new String(marker.content(), StandardCharsets.UTF_8);
        int newline = body.indexOf('\n');
        String header = newline < 0 ? body : body.substring(0, newline);
        String coordinate = newline < 0 ? "" : body.substring(newline + 1);
        int space = header.indexOf(' ');
        long version = Long.parseLong(header.substring(0, space));
        boolean removed = header.charAt(space + 1) == 'R';
        return new Entry(coordinate, version, removed, marker.token());
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required MessageDigest algorithm", impossible);
        }
    }

    private static String strip(String prefix) {
        int start = 0;
        int end = prefix.length();
        while (start < end && prefix.charAt(start) == '/') {
            start++;
        }
        while (end > start && prefix.charAt(end - 1) == '/') {
            end--;
        }
        return prefix.substring(start, end);
    }
}
