package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;

/**
 * The pull-through serve seam an edition contributes its screening through - the proxy-leg twin of the deploy edge's
 * {@link EdgeHooks}. A paid edition <em>plugs in</em> to the one shared free pull-through loop ({@link PullThroughCache})
 * rather than forking a second proxy controller; the free edition binds {@link #NONE}, so serving is byte-for-byte
 * unchanged (every hook is a serve-through/identity no-op that reads nothing).
 *
 * <p>Two capabilities, one per real #79 gap:
 * <ul>
 *   <li>{@link #screenFetch} decorates the upstream fetcher for the MISS leg - the seam the screening firewall
 *       ({@code ProxyScreen}/{@code HardenedScreen}) plugs into on paths that do not pass through the routed gateway
 *       (the free dispatcher loop, demo seeding, fixed-tenancy default upstreams). It is applied at the point the cache
 *       hands the fetcher to {@link ProxyFormat#proxy}.</li>
 *   <li>{@link #verifyHit} closes the cache-HIT bypass: a locally cached artifact is decided <em>before</em> any hit
 *       byte is served, so an edition can refuse a now-withheld artifact or re-screen a cached blob against the current
 *       gate. It returns a {@link HitDecision}, not a bare boolean, precisely so a refused/unverdicted hit is decided
 *       from the LOCAL bytes - never by dumping the request onto the upstream miss leg (see {@link HitDecision}).</li>
 * </ul>
 *
 * <p>The seam decides before any response byte, so the hit path's streaming {@link PullThroughCache} {@code Deferred}
 * is untouched - it never wraps, buffers or observes the served stream (§1). The free default and any well-behaved
 * implementation decide from pointer/metadata reads or a re-openable streamed read of the local blob, never by
 * buffering the whole body.
 */
public interface PullThroughHooks {

    /** The no-op default the free edition binds: {@link #verifyHit} serves every hit through and {@link #screenFetch}
     *  returns the fetcher unchanged, so the shared pull-through behaves exactly as if no edition plugged in - no extra
     *  store read on the hit path, the served bytes bit-identical. */
    PullThroughHooks NONE = new PullThroughHooks() {
    };

    /**
     * Verify a locally cached artifact against the current gate BEFORE a pull-through hit serves it, returning how the
     * cache should proceed. The free default returns {@link HitDecision#serveThrough()} - the local-first serve runs
     * exactly as today, and (because the enterprise treats "nothing durably local" as serve-through too) a path with no
     * cached blob simply flows on to the miss leg. An implementation reads only pointers/metadata or a re-openable
     * streamed read of the local blob to decide; it must never buffer the whole body (§1).
     *
     * <p>Called with the claiming {@code format}, the request {@code path} and the tenant-and-repository-scoped
     * {@code store}, so an implementation can resolve the local pointer and its verdict record without the exchange.
     */
    default HitDecision verifyHit(RepositoryFormat format, String path, ArtifactStore store) throws IOException {
        return HitDecision.serveThrough();
    }

    /**
     * Decorate the upstream fetcher for one request {@code path} before the miss-fetch runs - the seam the screening
     * firewall plugs into on the dispatcher-direct paths (the free dispatcher loop, demo seeding, fixed-tenancy default
     * upstreams) that do not pass through the routed gateway's own {@code screening()} decoration. The free default
     * returns {@code upstream} unchanged (identity), so the miss leg fetches exactly as today. The decoration is
     * path-bound, so the cache applies it per request at the point it invokes {@link ProxyFormat#proxy}.
     */
    default ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream) {
        return upstream;
    }

    /**
     * How a {@link #verifyHit} hook tells the cache to handle a locally cached artifact, decided BEFORE any hit byte is
     * served. Deliberately <em>not</em> a bare {@code boolean}: a "false" that dumped the request onto the upstream miss
     * leg would re-fetch cached-but-unverified bytes (wasteful, and closed-for-the-wrong-reason when upstream is down),
     * so a hit is always decided from the LOCAL bytes. Three outcomes:
     * <ul>
     *   <li>{@link #serveThrough()} - proceed with the format's local-first serve exactly as the free path does (the
     *       {@link #NONE} default, and the "nothing durably local / verdict still valid" answer). If the local-first
     *       turns out to be a 404, the request falls through to the (screened) miss leg as always.</li>
     *   <li>{@link #withhold()} - answer {@code 404} without serving the local bytes: a now-retracted or rejected
     *       artifact the current gate refuses. No upstream re-fetch - the withhold is final for this request.</li>
     *   <li>{@link #serveLocal(LocalServe)} - the edition takes over serving the local bytes itself, fail-closed: it
     *       re-screens the LOCAL blob (never the upstream) through a re-openable stream and either streams the verified
     *       bytes or answers {@code 404}. The enterprise {@code HardenedScreen.serveVerified} plugs in here.</li>
     * </ul>
     */
    sealed interface HitDecision {

        /** Proceed with the local-first serve exactly as the free path does - the {@link #NONE} default. */
        static HitDecision serveThrough() {
            return ServeThrough.INSTANCE;
        }

        /** Answer {@code 404} without serving the local bytes and without a miss-leg re-fetch. */
        static HitDecision withhold() {
            return Withhold.INSTANCE;
        }

        /** Hand serving of the local bytes to the edition, fail-closed over the local blob (no upstream fetch). */
        static HitDecision serveLocal(LocalServe serve) {
            return new ServeLocal(serve);
        }

        /** The {@link #serveThrough()} singleton. */
        final class ServeThrough implements HitDecision {
            private static final ServeThrough INSTANCE = new ServeThrough();

            private ServeThrough() {
            }
        }

        /** The {@link #withhold()} singleton. */
        final class Withhold implements HitDecision {
            private static final Withhold INSTANCE = new Withhold();

            private Withhold() {
            }
        }

        /** The {@link #serveLocal(LocalServe)} decision carrying the edition's local-serve callback. */
        record ServeLocal(LocalServe serve) implements HitDecision {
        }
    }

    /**
     * An edition's take-over serve of a cached hit's LOCAL bytes, fail-closed - the callback a
     * {@link HitDecision#serveLocal(LocalServe)} carries. It re-screens the local blob (through a re-openable stream,
     * never a heap {@code byte[]}) and writes the response through {@code exchange} itself: the verified bytes, or a
     * {@code 404} when the current gate refuses them. The cache invokes it in place of the local-first serve, so the
     * seam still decides before any byte is written and never wraps the streaming hit body.
     */
    @FunctionalInterface
    interface LocalServe {

        void serve(RepositoryFormat format, FormatExchange exchange, ArtifactStore store) throws IOException;
    }
}
