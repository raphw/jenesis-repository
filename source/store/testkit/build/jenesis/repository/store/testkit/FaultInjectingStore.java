package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * An {@link ArtifactStore} decorator that injects a store fault at a chosen point, so a crash-recovery test can drive
 * the exact failure a real backend outage would - a write that never lands, a read that fails, a compare-and-set that
 * loses its race - without a real object store or a killed process. It is the one shared fixture the crash-recovery
 * sweep is built on rather than each test hand-rolling a bespoke throwing decorator (the {@code ConflictOnceStore}
 * idiom the reconcile test grew), so both repositories' suites simulate a fault the same way.
 *
 * <p>A fault is armed against an operation kind ({@link Op}) and, optionally, a key pattern, and fires a bounded number
 * of matching calls before healing itself (single-shot by default), so a test can prove the immediate failure and then
 * that a retry or repair pass converges past it. Two failure shapes cover the store's contract:
 * <ul>
 *   <li>a thrown {@link IOException} - a {@linkplain #failNext crash before the write}, which never lands the mutation,
 *       or a {@linkplain #crashAfterWrite crash after the write} lands but before the caller learns it did (a lost ack
 *       / the process dying between two store writes); and</li>
 *   <li>a {@code writeVersioned} that {@linkplain #conflictNext returns false} - a benign concurrent conflict, so the
 *       compare-and-set retry loop is exercised rather than the exception path.</li>
 * </ul>
 * Every other call delegates unchanged, and each call is counted ({@link #calls}) so a test can assert a retry actually
 * re-attempted rather than silently dropping the write. Thread-safe: the matrices run concurrent writers against one
 * instance. This is a test double, never a production backend.
 */
public final class FaultInjectingStore implements ArtifactStore {

    /** The store operations a fault can be armed against. {@code WRITE_BLOB} carries no key, so it matches only a
     *  fault armed with {@link #anyKey}. */
    public enum Op {
        READ, OPEN, WRITE, WRITE_BLOB, WRITE_VERSIONED, DELETE, LIST, SIZE, EXISTS, READ_VERSIONED
    }

    private enum Mode {
        /** Throw before delegating - the mutation never lands (a crash before the write). */
        THROW_BEFORE,
        /** Delegate, then throw - the mutation lands but the caller sees a failure (a crash after the write). */
        THROW_AFTER,
        /** Return {@code false} from {@code writeVersioned} - a benign compare-and-set conflict, no exception. */
        CONFLICT
    }

    private static final class Rule {
        private final Op op;
        private final Predicate<String> key;
        private final Mode mode;
        private int skip;
        private int times;

        private Rule(Op op, Predicate<String> key, Mode mode, int skip, int times) {
            this.op = op;
            this.key = key;
            this.mode = mode;
            this.skip = skip;
            this.times = times;
        }
    }

    private final ArtifactStore delegate;
    private final List<Rule> rules = new ArrayList<>();
    private final Map<Op, Integer> counts = new EnumMap<>(Op.class);

    private FaultInjectingStore(ArtifactStore delegate) {
        this.delegate = delegate;
    }

    /** Wrap a delegate store; with no fault armed this is a transparent pass-through. */
    public static FaultInjectingStore wrap(ArtifactStore delegate) {
        return new FaultInjectingStore(delegate);
    }

    // --- fault arming (fluent; every method returns this) --------------------------------------------------------

    /** Any key - the fault matches every call of its operation kind. */
    public static Predicate<String> anyKey() {
        return key -> true;
    }

    /** Keys with this prefix (as {@code publish/}, {@code published/}, {@code blobs/}). */
    public static Predicate<String> keyPrefix(String prefix) {
        return key -> key != null && key.startsWith(prefix);
    }

    /** Keys containing this substring (as a coordinate or version fragment). */
    public static Predicate<String> keyContaining(String fragment) {
        return key -> key != null && key.contains(fragment);
    }

    /** Throw an {@link IOException} on the next call of {@code op} - a crash before the write, so the mutation never
     *  lands. Single-shot: the fault heals after one matching call. */
    public FaultInjectingStore failNext(Op op) {
        return failNextOn(op, anyKey());
    }

    /** Throw an {@link IOException} on the next call of {@code op} whose key matches - a crash before the write. */
    public FaultInjectingStore failNextOn(Op op, Predicate<String> key) {
        return arm(new Rule(op, key, Mode.THROW_BEFORE, 0, 1));
    }

    /** Throw on the {@code n}-th (1-based) matching call of {@code op} - the (n-1) before it pass through. */
    public FaultInjectingStore failNthOn(Op op, Predicate<String> key, int n) {
        return arm(new Rule(op, key, Mode.THROW_BEFORE, Math.max(0, n - 1), 1));
    }

    /** Throw on every matching call of {@code op} until {@link #heal}ed - a persistent outage rather than a blip. */
    public FaultInjectingStore failEveryOn(Op op, Predicate<String> key) {
        return arm(new Rule(op, key, Mode.THROW_BEFORE, 0, Integer.MAX_VALUE));
    }

    /** Delegate the next matching call of {@code op}, then throw - a crash after the write lands but before the caller
     *  learns it did (a lost ack, or the process dying between two store writes). */
    public FaultInjectingStore crashAfterWrite(Op op, Predicate<String> key) {
        return arm(new Rule(op, key, Mode.THROW_AFTER, 0, 1));
    }

    /** Make the next matching {@code writeVersioned} return {@code false} - a benign compare-and-set conflict, so the
     *  caller's retry loop runs rather than the exception path. */
    public FaultInjectingStore conflictNext(Predicate<String> key) {
        return arm(new Rule(Op.WRITE_VERSIONED, key, Mode.CONFLICT, 0, 1));
    }

    private synchronized FaultInjectingStore arm(Rule rule) {
        rules.add(rule);
        return this;
    }

    /** Clear every armed fault, so the store heals and delegates unchanged from here on. */
    public synchronized void heal() {
        rules.clear();
    }

    /** How many times {@code op} has been invoked - so a test can assert a retry actually re-attempted. */
    public synchronized int calls(Op op) {
        return counts.getOrDefault(op, 0);
    }

    /** The underlying store, for a direct assertion that bypasses any armed fault. */
    public ArtifactStore delegate() {
        return delegate;
    }

    // --- the fault decision --------------------------------------------------------------------------------------

    /** Record the call and consume the first matching rule, if any; the returned mode says how to fail (or null to
     *  proceed). {@code THROW_BEFORE} is acted on before delegating; the others after. */
    private synchronized Mode intercept(Op op, String key) {
        counts.merge(op, 1, Integer::sum);
        Iterator<Rule> iterator = rules.iterator();
        while (iterator.hasNext()) {
            Rule rule = iterator.next();
            if (rule.op != op || !rule.key.test(key)) {
                continue;
            }
            if (rule.skip > 0) {
                rule.skip--;
                continue;
            }
            Mode mode = rule.mode;
            if (--rule.times <= 0) {
                iterator.remove();
            }
            return mode;
        }
        return null;
    }

    // --- ArtifactStore --------------------------------------------------------------------------------------------

    @Override
    public ArtifactStore scope(String tenant) {
        // A scoped view shares this store's armed faults and counters, so a fault armed on the tenant-and-repository
        // scope the sweeps run against still fires - the same instance handles every scoped key.
        return new Scoped(delegate.scope(tenant));
    }

    @Override
    public boolean exists(String key) {
        Mode mode = intercept(Op.EXISTS, key);
        // exists() does not throw; a fault against it is a silent negative, the shape a lost read takes.
        return mode == null && delegate.exists(key);
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        Mode mode = intercept(Op.READ, key);
        if (mode == Mode.THROW_BEFORE) {
            throw fault(Op.READ, key);
        }
        delegate.read(key, out);
        if (mode == Mode.THROW_AFTER) {
            throw fault(Op.READ, key);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        Mode mode = intercept(Op.OPEN, key);
        if (mode == Mode.THROW_BEFORE) {
            throw fault(Op.OPEN, key);
        }
        InputStream stream = delegate.open(key);
        if (mode == Mode.THROW_AFTER) {
            stream.close();
            throw fault(Op.OPEN, key);
        }
        return stream;
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        Mode mode = intercept(Op.WRITE, key);
        if (mode == Mode.THROW_BEFORE) {
            throw fault(Op.WRITE, key);
        }
        delegate.write(key, in);
        if (mode == Mode.THROW_AFTER) {
            throw fault(Op.WRITE, key);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        Mode mode = intercept(Op.WRITE_BLOB, null);
        if (mode == Mode.THROW_BEFORE) {
            throw fault(Op.WRITE_BLOB, "blobs/");
        }
        String hash = delegate.writeBlob(in);
        if (mode == Mode.THROW_AFTER) {
            throw fault(Op.WRITE_BLOB, "blobs/" + hash);
        }
        return hash;
    }

    @Override
    public long size(String key) throws IOException {
        Mode mode = intercept(Op.SIZE, key);
        if (mode == Mode.THROW_BEFORE || mode == Mode.THROW_AFTER) {
            throw fault(Op.SIZE, key);
        }
        return delegate.size(key);
    }

    @Override
    public void delete(String key) throws IOException {
        Mode mode = intercept(Op.DELETE, key);
        if (mode == Mode.THROW_BEFORE) {
            throw fault(Op.DELETE, key);
        }
        delegate.delete(key);
        if (mode == Mode.THROW_AFTER) {
            throw fault(Op.DELETE, key);
        }
    }

    @Override
    public List<String> list(String prefix) {
        // list() throws nothing in the SPI, so a fault against it is a silent empty listing - the shape a real
        // backend outage takes there (the filesystem store maps an enumeration IOException to an empty list).
        return intercept(Op.LIST, prefix) != null ? List.of() : delegate.list(prefix);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        Mode mode = intercept(Op.READ_VERSIONED, key);
        if (mode == Mode.THROW_BEFORE || mode == Mode.THROW_AFTER) {
            throw fault(Op.READ_VERSIONED, key);
        }
        return delegate.readVersioned(key);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        Mode mode = intercept(Op.WRITE_VERSIONED, key);
        if (mode == Mode.THROW_BEFORE) {
            throw fault(Op.WRITE_VERSIONED, key);
        }
        if (mode == Mode.CONFLICT) {
            return false;                                        // the injected concurrent conflict, not an exception
        }
        boolean written = delegate.writeVersioned(key, content, expected);
        if (mode == Mode.THROW_AFTER) {
            throw fault(Op.WRITE_VERSIONED, key);
        }
        return written;
    }

    private static IOException fault(Op op, String key) {
        return new IOException("injected " + op + " fault at " + key);
    }

    /** A scoped view that routes every call back through the parent's fault decision, so an armed fault fires on the
     *  scoped keys the sweeps use ({@code publish/...}, {@code published/...}) exactly as it would unscoped. */
    private final class Scoped implements ArtifactStore {

        private final ArtifactStore scoped;

        private Scoped(ArtifactStore scoped) {
            this.scoped = scoped;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new Scoped(scoped.scope(tenant));
        }

        @Override
        public boolean exists(String key) {
            return intercept(Op.EXISTS, key) == null && scoped.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            Mode mode = intercept(Op.READ, key);
            if (mode == Mode.THROW_BEFORE) {
                throw fault(Op.READ, key);
            }
            scoped.read(key, out);
            if (mode == Mode.THROW_AFTER) {
                throw fault(Op.READ, key);
            }
        }

        @Override
        public InputStream open(String key) throws IOException {
            Mode mode = intercept(Op.OPEN, key);
            if (mode == Mode.THROW_BEFORE) {
                throw fault(Op.OPEN, key);
            }
            InputStream stream = scoped.open(key);
            if (mode == Mode.THROW_AFTER) {
                stream.close();
                throw fault(Op.OPEN, key);
            }
            return stream;
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            Mode mode = intercept(Op.WRITE, key);
            if (mode == Mode.THROW_BEFORE) {
                throw fault(Op.WRITE, key);
            }
            scoped.write(key, in);
            if (mode == Mode.THROW_AFTER) {
                throw fault(Op.WRITE, key);
            }
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            Mode mode = intercept(Op.WRITE_BLOB, null);
            if (mode == Mode.THROW_BEFORE) {
                throw fault(Op.WRITE_BLOB, "blobs/");
            }
            String hash = scoped.writeBlob(in);
            if (mode == Mode.THROW_AFTER) {
                throw fault(Op.WRITE_BLOB, "blobs/" + hash);
            }
            return hash;
        }

        @Override
        public long size(String key) throws IOException {
            if (intercept(Op.SIZE, key) != null) {
                throw fault(Op.SIZE, key);
            }
            return scoped.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            Mode mode = intercept(Op.DELETE, key);
            if (mode == Mode.THROW_BEFORE) {
                throw fault(Op.DELETE, key);
            }
            scoped.delete(key);
            if (mode == Mode.THROW_AFTER) {
                throw fault(Op.DELETE, key);
            }
        }

        @Override
        public List<String> list(String prefix) {
            // As on the outer store: an armed LIST fault is a silent empty listing, the SPI's outage shape.
            return intercept(Op.LIST, prefix) != null ? List.of() : scoped.list(prefix);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            if (intercept(Op.READ_VERSIONED, key) != null) {
                throw fault(Op.READ_VERSIONED, key);
            }
            return scoped.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            Mode mode = intercept(Op.WRITE_VERSIONED, key);
            if (mode == Mode.THROW_BEFORE) {
                throw fault(Op.WRITE_VERSIONED, key);
            }
            if (mode == Mode.CONFLICT) {
                return false;
            }
            boolean written = scoped.writeVersioned(key, content, expected);
            if (mode == Mode.THROW_AFTER) {
                throw fault(Op.WRITE_VERSIONED, key);
            }
            return written;
        }
    }
}
