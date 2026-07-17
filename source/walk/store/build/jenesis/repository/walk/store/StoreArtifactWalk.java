package build.jenesis.repository.walk.store;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;

import module java.base;

/**
 * The reference {@link ArtifactWalk} over the store's own key layout. Enumeration is a depth-first descent that
 * visits sibling names in lexicographic order - one total order over all keys - and it consumes the store
 * exclusively through {@link ArtifactStore#page}, so a flat millions-entry namespace is paged, never buffered, and
 * a resume deep inside one is a seek on a backend that pages natively. A key is a leaf where an object is stored
 * ({@link ArtifactStore#exists}); a name with children is a container to descend (the store's layouts never make one
 * key both, matching a filesystem, where that is impossible by construction).
 *
 * <p>The total order a name-sorted descent produces is <em>path order</em>: keys compare character by character
 * with the {@code '/'} separator below every other character, so a subtree ({@code app/...}) sits wholly before a
 * longer sibling name it prefixes ({@code app.txt}) - where plain string order would interleave the two, a resume
 * cursor of {@code app/nested} would wrongly exclude the not-yet-visited {@code app.txt}. Every cursor and range
 * comparison therefore goes through {@link #order}, keeping the arithmetic exactly consistent with the visit
 * sequence.
 *
 * <p>Pass state is durable in the walked store and nowhere else. The manifest
 * ({@code walks/<consumer>/manifest}) carries the pass generation, its roots and the static segment plan;
 * create-if-absent is the coordinator election, so no leader exists afterwards. Each segment
 * ({@code walks/<consumer>/segments/<nn>}) is one compare-and-set object embedding its claim
 * ({@code state, holder, expiry, cursor}): a claim is a CAS over pending-or-expired (never a live holder's - refuse,
 * don't steal), every checkpoint commit renews the lease in the same write, and a commit that loses the CAS means
 * the claim was reclaimed after expiry, so the worker stops. A taken-over segment resumes from its last committed
 * cursor: node death costs at most one checkpoint stride of re-visits, never a restart.
 *
 * <p>The segment plan is static per pass: each root's children are paged up to a planning cap and packed into
 * contiguous ranges toward the {@code jenesis.walk.segments} target; a root whose fan-out exceeds the cap and whose
 * sampled children are all long lowercase hex (the content-addressed {@code blobs/} namespace) is cut by leading hex
 * byte instead - uniform by construction, with no listing at all - and any other over-cap root conservatively stays
 * one segment. Adaptive mid-pass splitting is deliberately out of scope: splitting a claimed range safely needs a
 * two-object CAS the store does not have.
 */
public final class StoreArtifactWalk implements ArtifactWalk {

    /** Sibling names fetched per {@link ArtifactStore#page} call - the only enumeration buffer. */
    private static final int PAGE = 1000;

    private final int checkpoint;
    private final int segments;
    private final Duration ttl;
    private final Clock clock;
    /** This instance's identity inside segment claims; each {@link #walk} call suffixes a worker counter. */
    private final String node = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong workers = new AtomicLong();

    public StoreArtifactWalk(int checkpoint, int segments, Duration ttl, Clock clock) {
        if (checkpoint < 1 || segments < 1 || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("checkpoint and segments must be positive and ttl non-zero");
        }
        this.checkpoint = checkpoint;
        this.segments = segments;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public WalkPass walk(ArtifactStore store, String consumer, List<String> roots, KeyVisitor visitor)
            throws IOException {
        String scope = ArtifactStore.segment(consumer);
        String holder = node + "/" + workers.incrementAndGet();
        Manifest manifest = manifest(store, scope, roots);
        while (true) {
            Claimed claimed = claim(store, scope, manifest, holder);
            if (claimed == null) {
                return finish(store, scope, manifest);
            }
            new Worker(store, scope, manifest, claimed, holder, visitor).run();
        }
    }

    @Override
    public Optional<WalkPass> pass(ArtifactStore store, String consumer) throws IOException {
        String scope = ArtifactStore.segment(consumer);
        Manifest manifest = parseManifest(store.readVersioned(manifestKey(scope)).orElse(null));
        return manifest == null ? Optional.empty() : Optional.of(pass(store, scope, manifest));
    }

    @Override
    public List<WalkSegment> segments(ArtifactStore store, String consumer) throws IOException {
        String scope = ArtifactStore.segment(consumer);
        Manifest manifest = parseManifest(store.readVersioned(manifestKey(scope)).orElse(null));
        if (manifest == null) {
            return List.of();
        }
        List<WalkSegment> result = new ArrayList<>();
        for (int index = 0; index < manifest.ranges().size(); index++) {
            Range range = manifest.ranges().get(index);
            Segment segment = parseSegment(store.readVersioned(segmentKey(scope, index)).orElse(null));
            if (segment == null || segment.generation() != manifest.generation()) {
                // Never started this pass (or a stale leftover of an earlier one): pending from the plan.
                result.add(new WalkSegment(manifest.generation(), index, range.root(), range.from(), range.to(),
                        WalkSegment.State.PENDING, null, null, null));
            } else {
                result.add(new WalkSegment(segment.generation(), index, range.root(), range.from(), range.to(),
                        segment.state(), segment.holder(), segment.expiry(), segment.cursor()));
            }
        }
        return result;
    }

    // --- the pass manifest -----------------------------------------------------------------------------------

    /** The static plan and claim state of one pass; the store object is the only copy, this is a parsed view. */
    private record Manifest(long generation, Instant started, List<String> roots, List<Range> ranges,
                            boolean complete) {
    }

    /** A half-open key slice {@code [from, to)} under one root; {@code null} bounds run to the root's edges. */
    private record Range(String root, String from, String to) {
    }

    /** Read the current manifest, starting a fresh pass (create-if-absent / complete-then-increment, both CAS -
     *  the coordinator election) when none is running. A lost race re-reads the winner's pass and joins it. */
    private Manifest manifest(ArtifactStore store, String scope, List<String> roots) throws IOException {
        String key = manifestKey(scope);
        while (true) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Manifest manifest = parseManifest(current.orElse(null));
            if (manifest != null && !manifest.complete()) {
                return manifest;
            }
            // A corrupt manifest parses null but still occupies the CAS slot: base the new generation on the
            // clock so stale segment objects (whose generation is unknowable) can never masquerade as current.
            long generation = manifest != null ? manifest.generation() + 1
                    : current.isPresent() ? Math.max(1, clock.millis()) : 1;
            List<String> ordered = roots.stream().distinct().sorted().toList();
            Manifest fresh = new Manifest(generation, clock.instant(), ordered, plan(store, ordered), false);
            if (store.writeVersioned(key, bytes(serialize(fresh)),
                    current.map(ArtifactStore.Versioned::token).orElse(null))) {
                return fresh;
            }
        }
    }

    /** Cut each root into contiguous key ranges toward the global segment target (split evenly across roots). */
    private List<Range> plan(ArtifactStore store, List<String> roots) {
        int target = Math.max(1, segments / Math.max(1, roots.size()));
        int cap = Math.max(64, 4 * target);
        List<Range> ranges = new ArrayList<>();
        for (String root : roots) {
            List<String> children = new ArrayList<>();
            store.page(root, "", cap, children::add);
            if (children.size() >= cap) {
                if (children.stream().allMatch(StoreArtifactWalk::hex)) {
                    // The flat content-addressed namespace: cut by leading hex byte, uniform by construction,
                    // without listing the (possibly millions of) children at all.
                    List<String> cuts = new ArrayList<>();
                    for (int value = 0; value < 256; value++) {
                        cuts.add(root + "/" + String.format("%02x", value));
                    }
                    pack(root, cuts, target, ranges);
                } else {
                    // Over-cap fan-out with no uniform naming to cut by: one conservative segment (the recorded
                    // scalability limit - a static plan cannot balance what it cannot enumerate cheaply).
                    ranges.add(new Range(root, null, null));
                }
            } else if (children.isEmpty() || children.size() >= target) {
                pack(root, keys(root, children), target, ranges);
            } else {
                // Too few children to meet the target: descend one level and cut at grandchild boundaries.
                List<String> cuts = new ArrayList<>();
                for (String child : children) {
                    List<String> grand = new ArrayList<>();
                    store.page(root + "/" + child, "", cap, grand::add);
                    if (grand.isEmpty()) {
                        cuts.add(root + "/" + child);
                    } else {
                        cuts.addAll(keys(root + "/" + child, grand));
                    }
                }
                pack(root, cuts, target, ranges);
            }
        }
        return List.copyOf(ranges);
    }

    private static List<String> keys(String prefix, List<String> names) {
        return names.stream().map(name -> prefix + "/" + name).toList();
    }

    /** Pack sorted cut candidates into at most {@code target} contiguous ranges under {@code root}. */
    private static void pack(String root, List<String> cuts, int target, List<Range> ranges) {
        int count = cuts.isEmpty() ? 1 : Math.min(target, cuts.size());
        String from = null;
        for (int index = 1; index < count; index++) {
            String to = cuts.get(index * cuts.size() / count);
            ranges.add(new Range(root, from, to));
            from = to;
        }
        ranges.add(new Range(root, from, null));
    }

    private static boolean hex(String name) {
        if (name.length() < 32) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    // --- segment claims --------------------------------------------------------------------------------------

    /** A parsed segment-state object; {@code null} fields where the object never recorded them. */
    private record Segment(long generation, WalkSegment.State state, String holder, Instant expiry, String cursor) {
    }

    /** A freshly won claim: the segment, the cursor to resume from, and the CAS token of the claiming write. */
    private record Claimed(int index, Range range, String cursor, Object token) {
    }

    /** Scan the plan in order and CAS-claim the first pending, expired or stale-generation segment; {@code null}
     *  when nothing is claimable (all done, or live holders own the rest). A lost CAS just moves on. */
    private Claimed claim(ArtifactStore store, String scope, Manifest manifest, String holder) throws IOException {
        for (int index = 0; index < manifest.ranges().size(); index++) {
            String key = segmentKey(scope, index);
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Segment segment = parseSegment(current.orElse(null));
            if (segment != null && segment.generation() > manifest.generation()) {
                // The pass turned over to a newer generation while this worker held a manifest read at pass entry.
                // The newer segment is a live claim of the pass that superseded ours - refuse-don't-steal applies
                // across generations too, so leave it untouched (stealing it would reset a live holder's cursor and
                // ping-pong the two passes). Our own pass is finished; skipping every newer segment lets claim() run
                // dry and walk() return through finish(), and the next call reads the current manifest.
                continue;
            }
            boolean stale = segment == null || segment.generation() != manifest.generation();
            Instant now = clock.instant();
            if (!stale && (segment.state() == WalkSegment.State.DONE
                    || segment.state() == WalkSegment.State.CLAIMED && segment.expiry() != null
                            && segment.expiry().isAfter(now))) {
                continue;
            }
            String cursor = stale ? null : segment.cursor();
            byte[] content = bytes(serialize(manifest.generation(), index, manifest.ranges().get(index),
                    WalkSegment.State.CLAIMED, holder, now.plus(ttl), cursor));
            if (!store.writeVersioned(key, content, current.map(ArtifactStore.Versioned::token).orElse(null))) {
                continue; // another worker won this segment between the read and the write
            }
            Optional<ArtifactStore.Versioned> won = store.readVersioned(key);
            Segment ours = parseSegment(won.orElse(null));
            if (ours == null || !holder.equals(ours.holder())) {
                continue; // taken over before the token read - treat as a lost race
            }
            return new Claimed(index, manifest.ranges().get(index), cursor, won.get().token());
        }
        return null;
    }

    /** Count the pass's finished segments and, when every one is done, CAS-flip the manifest to complete (all
     *  finishers may race here; the flip is idempotent and one write wins). */
    private WalkPass finish(ArtifactStore store, String scope, Manifest manifest) throws IOException {
        if (done(store, scope, manifest) == manifest.ranges().size()) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(manifestKey(scope));
            Manifest latest = parseManifest(current.orElse(null));
            if (latest != null && latest.generation() == manifest.generation() && !latest.complete()) {
                Manifest complete = new Manifest(latest.generation(), latest.started(), latest.roots(),
                        latest.ranges(), true);
                store.writeVersioned(manifestKey(scope), bytes(serialize(complete)), current.get().token());
            }
            Manifest flipped = parseManifest(store.readVersioned(manifestKey(scope)).orElse(null));
            return pass(store, scope, flipped != null ? flipped : manifest);
        }
        return pass(store, scope, manifest);
    }

    private int done(ArtifactStore store, String scope, Manifest manifest) throws IOException {
        int done = 0;
        for (int index = 0; index < manifest.ranges().size(); index++) {
            Segment segment = parseSegment(store.readVersioned(segmentKey(scope, index)).orElse(null));
            if (segment != null && segment.generation() == manifest.generation()
                    && segment.state() == WalkSegment.State.DONE) {
                done++;
            }
        }
        return done;
    }

    private WalkPass pass(ArtifactStore store, String scope, Manifest manifest) throws IOException {
        return new WalkPass(manifest.generation(), manifest.started(), manifest.roots(), manifest.ranges().size(),
                done(store, scope, manifest), manifest.complete() ? WalkPass.Status.COMPLETE : WalkPass.Status.ACTIVE);
    }

    // --- walking one claimed segment ---------------------------------------------------------------------------

    /** The renewal CAS lost: the claim expired and another worker took the segment over - stop, don't steal back. */
    private static final class ClaimLost extends IOException {
        ClaimLost() {
            super("The segment claim expired and was taken over");
        }
    }

    /** One worker executing one claimed segment: the ordered depth-first descent, the bounds arithmetic, and the
     *  checkpoint commit that doubles as lease renewal. */
    private final class Worker {

        private final ArtifactStore store;
        private final String key;
        private final long generation;
        private final int index;
        private final Range range;
        private final String holder;
        private final String from;
        private final String to;
        private final String resume;
        private final KeyVisitor visitor;
        private Object token;
        private String cursor;
        private long count;

        private Worker(ArtifactStore store, String scope, Manifest manifest, Claimed claimed, String holder,
                       KeyVisitor visitor) {
            this.store = store;
            this.key = segmentKey(scope, claimed.index());
            this.generation = manifest.generation();
            this.index = claimed.index();
            this.range = claimed.range();
            this.holder = holder;
            this.from = claimed.range().from();
            this.to = claimed.range().to();
            this.resume = claimed.cursor();
            this.visitor = visitor;
            this.token = claimed.token();
            this.cursor = claimed.cursor();
        }

        /** Walk the range from its cursor; a lost renewal stops quietly (the new holder finishes the segment), a
         *  visitor failure propagates with the claim left to expire and resume from the last committed cursor. */
        private void run() throws IOException {
            try {
                node(range.root(), visitor);
                commit(WalkSegment.State.DONE);
            } catch (ClaimLost _) {
                // A lost renewal mid-walk, or a lost CAS on the terminal DONE commit itself, both mean the claim
                // was reclaimed while this worker held it - the new holder finishes the segment, so stop quietly
                // rather than failing the whole pass. A segment shorter than one checkpoint stride never renews
                // between claim and completion, so its DONE commit is the first and only place its lease is tested.
            }
        }

        private void emit(String key, KeyVisitor visitor) throws IOException {
            visitor.visit(key);
            cursor = key;
            if (++count % checkpoint == 0) {
                commit(WalkSegment.State.CLAIMED);
            }
        }

        /** Commit cursor + state; the same write renews the lease. Losing the CAS proves the claim was reclaimed.
         *  The visitor flushes first ({@link KeyVisitor#beforeCheckpoint}), so a committed cursor never lies about
         *  a derived write still sitting in a consumer's buffer - a failed flush leaves the previous cursor
         *  standing and the re-visit replays what the flush lost. */
        private void commit(WalkSegment.State state) throws IOException {
            visitor.beforeCheckpoint(cursor);
            byte[] content = bytes(serialize(generation, index, range, state, holder, clock.instant().plus(ttl),
                    cursor));
            if (!store.writeVersioned(key, content, token)) {
                throw new ClaimLost();
            }
            // Re-read for the next compare-and-set's token - and verify the object is still ours: a claim that
            // expired and was taken over between the write and this read must not hand us the thief's token, or
            // the next commit would steal the segment back from its live holder.
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Segment ours = parseSegment(current.orElse(null));
            if (ours == null || !holder.equals(ours.holder())) {
                throw new ClaimLost();
            }
            token = current.get().token();
        }

        /** Ordered depth-first descent: a stored object is a leaf, anything with children a container. */
        private void node(String key, KeyVisitor visitor) throws IOException {
            if (store.exists(key)) {
                if (includes(key)) {
                    emit(key, visitor);
                }
                return;
            }
            if (!intersects(key)) {
                return;
            }
            String startAfter = "";
            String low = lower();
            if (low != null && low.startsWith(key + "/")) {
                // The resume point (or range start) lies inside this container: handle its child on that path
                // first, then page strictly after it - so a resume deep inside a huge flat namespace is a seek,
                // not a re-list. A cut-point name that is no real child probes as neither leaf nor container
                // and falls through harmlessly.
                String rest = low.substring(key.length() + 1);
                int slash = rest.indexOf('/');
                String child = slash < 0 ? rest : rest.substring(0, slash);
                node(key + "/" + child, visitor);
                startAfter = child;
            }
            while (true) {
                List<String> children = new ArrayList<>();
                store.page(key, startAfter, PAGE, children::add);
                for (String child : children) {
                    String full = key + "/" + child;
                    if (to != null && order(full, to) >= 0) {
                        return; // sorted siblings: nothing at or past the upper bound can be in range
                    }
                    node(full, visitor);
                }
                if (children.size() < PAGE) {
                    return;
                }
                startAfter = children.getLast();
            }
        }

        /** Whether a leaf key is inside the range and past the resume cursor ({@code from} inclusive, {@code to}
         *  and the cursor exclusive). */
        private boolean includes(String key) {
            return (from == null || order(key, from) >= 0)
                    && (to == null || order(key, to) < 0)
                    && (resume == null || order(key, resume) > 0);
        }

        /** Whether any key under {@code prefix/} can still fall inside the range and past the cursor. In path
         *  order every such key sorts at or above {@code prefix + "/"} and strictly below {@code prefix + "0"}
         *  ({@code '0'} being the character after {@code '/'}), so those two strings bound the whole subtree. */
        private boolean intersects(String prefix) {
            String floor = prefix + "/";
            String ceiling = prefix + "0";
            return (to == null || order(floor, to) < 0)
                    && (from == null || order(ceiling, from) > 0)
                    && (resume == null || order(ceiling, resume) > 0);
        }

        /** The seek target inside this segment: the resume cursor when it lies past the range start, else the
         *  range start; {@code null} when the walk starts at the beginning. */
        private String lower() {
            if (resume != null) {
                return from == null || order(resume, from) >= 0 ? resume : from;
            }
            return from;
        }
    }

    /**
     * The walk's total key order - <em>path order</em>, what a name-sorted depth-first descent visits: character
     * by character with {@code '/'} sorting below every other character, a shorter key before any longer one it
     * prefixes. Plain string order would put a subtree {@code app/...} after a sibling leaf {@code app.txt}
     * ({@code '.'} sorts below {@code '/'}) although the descent, which orders siblings by name, visits the
     * {@code app} subtree first; comparing under path order keeps cursors and range bounds exactly consistent
     * with the visit sequence.
     */
    static int order(String left, String right) {
        int length = Math.min(left.length(), right.length());
        for (int index = 0; index < length; index++) {
            char first = left.charAt(index), second = right.charAt(index);
            if (first != second) {
                if (first == '/') {
                    return -1;
                }
                if (second == '/') {
                    return 1;
                }
                return Character.compare(first, second);
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    // --- store object (de)serialisation ------------------------------------------------------------------------

    private static String manifestKey(String scope) {
        return "walks/" + scope + "/manifest";
    }

    private static String segmentKey(String scope, int index) {
        return "walks/" + scope + "/segments/" + String.format("%03d", index);
    }

    private Properties serialize(Manifest manifest) {
        Properties properties = new Properties();
        properties.setProperty("generation", Long.toString(manifest.generation()));
        properties.setProperty("started", manifest.started().toString());
        properties.setProperty("status", manifest.complete() ? "complete" : "active");
        for (int index = 0; index < manifest.roots().size(); index++) {
            properties.setProperty("root." + index, manifest.roots().get(index));
        }
        properties.setProperty("segments", Integer.toString(manifest.ranges().size()));
        for (int index = 0; index < manifest.ranges().size(); index++) {
            Range range = manifest.ranges().get(index);
            properties.setProperty("segment." + index + ".root", range.root());
            if (range.from() != null) {
                properties.setProperty("segment." + index + ".from", range.from());
            }
            if (range.to() != null) {
                properties.setProperty("segment." + index + ".to", range.to());
            }
        }
        return properties;
    }

    /** Parse a manifest object; {@code null} for an absent or unparseable one (self-heal: a fresh pass replaces
     *  it by CAS on the same token, so corruption is never fatal). */
    private static Manifest parseManifest(ArtifactStore.Versioned versioned) {
        if (versioned == null) {
            return null;
        }
        try {
            Properties properties = properties(versioned.content());
            long generation = Long.parseLong(properties.getProperty("generation"));
            Instant started = Instant.parse(properties.getProperty("started"));
            boolean complete = "complete".equals(properties.getProperty("status"));
            List<String> roots = new ArrayList<>();
            for (int index = 0; properties.getProperty("root." + index) != null; index++) {
                roots.add(properties.getProperty("root." + index));
            }
            int count = Integer.parseInt(properties.getProperty("segments"));
            List<Range> ranges = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String root = properties.getProperty("segment." + index + ".root");
                if (root == null) {
                    return null;
                }
                ranges.add(new Range(root,
                        properties.getProperty("segment." + index + ".from"),
                        properties.getProperty("segment." + index + ".to")));
            }
            return new Manifest(generation, started, List.copyOf(roots), List.copyOf(ranges), complete);
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private Properties serialize(long generation, int index, Range range, WalkSegment.State state, String holder,
                                 Instant expiry, String cursor) {
        Properties properties = new Properties();
        properties.setProperty("generation", Long.toString(generation));
        properties.setProperty("index", Integer.toString(index));
        properties.setProperty("root", range.root());
        if (range.from() != null) {
            properties.setProperty("from", range.from());
        }
        if (range.to() != null) {
            properties.setProperty("to", range.to());
        }
        properties.setProperty("state", state.name().toLowerCase(Locale.ROOT));
        properties.setProperty("holder", holder);
        properties.setProperty("expiry", Long.toString(expiry.toEpochMilli()));
        if (cursor != null) {
            properties.setProperty("cursor", cursor);
        }
        return properties;
    }

    /** Parse a segment-state object; {@code null} for an absent or unparseable one (claimable as if pending). */
    private static Segment parseSegment(ArtifactStore.Versioned versioned) {
        if (versioned == null) {
            return null;
        }
        try {
            Properties properties = properties(versioned.content());
            long generation = Long.parseLong(properties.getProperty("generation"));
            WalkSegment.State state = WalkSegment.State.valueOf(
                    properties.getProperty("state").toUpperCase(Locale.ROOT));
            String holder = properties.getProperty("holder");
            String expiry = properties.getProperty("expiry");
            return new Segment(generation, state, holder,
                    expiry == null ? null : Instant.ofEpochMilli(Long.parseLong(expiry)),
                    properties.getProperty("cursor"));
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private static byte[] bytes(Properties properties) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, null);
        return out.toByteArray();
    }

    private static Properties properties(byte[] content) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(content));
        return properties;
    }
}
