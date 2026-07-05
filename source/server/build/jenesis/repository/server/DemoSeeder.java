package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo mode: flag-guarded seeding of a fresh, empty repository with real artifacts so an evaluator has data to look
 * at - browse rows, proxied artifacts, and (deliberately, for old benign-but-vulnerable coordinates) a lit-up
 * vulnerability and quarantine surface. It is framework-neutral - a shell wires the trigger and passes the
 * already-scoped store - and carries no fetch machinery of its own: it collects every installed
 * {@link RepositoryFormat#demoArtifacts() format's suggestions} and pulls each one through that format's own
 * pull-through path (the normal {@link FormatDispatcher} loop over the format's {@link ProxyFormat#defaultUpstream()
 * declared upstream}), so every artifact streams through the normal pipeline, the inspectors screen the proxy leg,
 * and the compliance gate populates itself. No blob is embedded here and no actual malicious bytes are ever fetched -
 * the suggestions are ordinary, harmless releases whose only sin is being old enough to have a known advisory.
 *
 * <p><strong>The hard guard: only a completely empty artifact space is seeded.</strong> {@link #seed} refuses a
 * non-empty store with a log line and fetches nothing, which makes re-seeding structurally impossible (a seeded
 * repository is no longer empty) and turns the flag on in a used deployment into a harmless no-op. Seeding is
 * best-effort over the public registries: a per-artifact fetch or gate failure is logged and tolerated so one
 * unavailable suggestion never stops the rest. A shell runs this on a background thread after boot (never blocking
 * it), only when the {@code demo} flag is on.
 */
public final class DemoSeeder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoSeeder.class);

    private final List<RepositoryFormat> formats;
    private final ProxyFormat.Fetcher fetcher;

    /** @param formats the installed formats (their {@code demoArtifacts()} are collected and their
     *  {@code defaultUpstream()} drives the pull-through), and {@code fetcher} the upstream fetcher the demo pulls
     *  through - the real HTTP fetcher in a deployment, a stub over canned bytes in a test. */
    public DemoSeeder(List<RepositoryFormat> formats, ProxyFormat.Fetcher fetcher) {
        this.formats = List.copyOf(formats);
        this.fetcher = fetcher;
    }

    /** Every installed format's suggested demo request paths, gathered in format-then-declaration order. */
    public List<String> suggestions() {
        List<String> paths = new ArrayList<>();
        for (RepositoryFormat format : formats) {
            paths.addAll(format.demoArtifacts());
        }
        return List.copyOf(paths);
    }

    /**
     * Whether the given (tenant-and-repository-scoped) store is a completely empty artifact space: no
     * content-addressed blobs and no publish pointers (an accepted, quarantined or rejected artifact all leave one
     * or the other). Configuration ({@code config/settings/...}) is not artifact content and so does not count - a
     * demo gate config already layered in does not make the space non-empty.
     */
    public static boolean empty(ArtifactStore store) {
        return store.list("blobs").isEmpty() && store.list("publish").isEmpty();
    }

    /**
     * Seed the scoped store with every installed format's demo suggestions, each pulled through its format's own
     * upstream so the normal gate/inspector pipeline runs on the proxy leg. Refuses (and fetches nothing) unless the
     * artifact space is completely empty. Best-effort per artifact: a failure is logged and tolerated. Returns a
     * small summary of what ran.
     */
    public Result seed(ArtifactStore store) throws IOException {
        if (!empty(store)) {
            LOGGER.info("Demo seeding refused: the artifact space is not empty "
                    + "(a seeded or in-use repository is never re-seeded)");
            return new Result(false, 0, 0);
        }
        // Pull each suggestion through the format's own declared upstream, so demo mode works even when the
        // deployment configured no proxy upstreams: defaultUpstream() where a format names one, nothing where it does
        // not (a hosted-only format simply seeds nothing).
        Map<String, URI> upstreams = new LinkedHashMap<>();
        for (RepositoryFormat format : formats) {
            if (format instanceof ProxyFormat proxy) {
                proxy.defaultUpstream().ifPresent(upstream -> upstreams.put(format.name(), upstream));
            }
        }
        FormatDispatcher dispatcher = new FormatDispatcher(formats, upstreams, fetcher);
        List<String> suggestions = suggestions();
        int seeded = 0;
        int unavailable = 0;
        for (String path : suggestions) {
            try {
                SeedExchange exchange = new SeedExchange(path);
                boolean claimed = dispatcher.dispatch(exchange, store);
                if (!claimed) {
                    unavailable++;
                    LOGGER.info("Demo suggestion claimed by no installed format: {}", path);
                } else if (served(exchange.status()) || quarantined(store, path)) {
                    // Served (a 2xx pulled the artifact through) or gate-withheld (the gate quarantined it, so a read
                    // is a 404 yet the quarantine surface is populated) - both are a successful demo outcome.
                    seeded++;
                } else {
                    unavailable++;
                    LOGGER.info("Demo artifact not available upstream ({}): {}", exchange.status(), path);
                }
            } catch (IOException | RuntimeException exception) {
                unavailable++;
                LOGGER.info("Demo artifact fetch failed for {}: {}", path, exception.getMessage());
            }
        }
        LOGGER.info("Demo seeding complete: {} of {} suggestions seeded ({} unavailable)",
                seeded, suggestions.size(), unavailable);
        return new Result(true, seeded, unavailable);
    }

    private static boolean served(int status) {
        return status >= 200 && status < 300;
    }

    /** Whether a gate quarantined the pulled-through artifact - the withhold pointer a screen links for a QUARANTINE
     *  verdict, so a suggestion whose read is a 404 (withheld) still counts as seeded rather than unavailable. */
    private static boolean quarantined(ArtifactStore store, String path) throws IOException {
        return store.readVersioned("publish/quarantine" + path).isPresent();
    }

    /** The outcome of a seed pass: whether it {@code ran} at all (the store was empty), and how many suggestions were
     *  {@code seeded} versus {@code unavailable} upstream. */
    public record Result(boolean ran, int seeded, int unavailable) {
    }

    /** A headless {@code GET} {@link FormatExchange} for one demo suggestion: the pull-through streams the artifact
     *  into the store as a side effect, so this discards the served body and only captures the status. It carries no
     *  request headers, so no runtime toggle or explode header rides a demo fetch. */
    private static final class SeedExchange implements FormatExchange {

        private final String path;
        private int status;

        private SeedExchange(String path) {
            this.path = path;
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
            // a demo fetch discards response headers; the artifact is already in the store
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return OutputStream.nullOutputStream();
        }

        private int status() {
            return status;
        }
    }
}
