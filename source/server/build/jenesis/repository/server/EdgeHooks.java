package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

import module java.base;

/**
 * The edge plug-in seam an edition contributes its own ingress concerns through, so a paid edition <em>plugs in</em> to
 * the one shared free screening edge ({@link ScreenedDispatch}) rather than forking a second deploy controller. The
 * free edition binds the {@link #NONE no-op default}, so its write choreography (screen &rarr; lay out &rarr;
 * {@link build.jenesis.repository.store.Publication#published published}; {@code QUARANTINE} &rarr; {@code 202};
 * {@code REJECT} &rarr; {@code 422}) is byte-for-byte unchanged. The enterprise edition supplies an implementation that
 * binds a tenant, refuses an immutable-release overwrite, records a quarantine-dispatch replay context and observes each
 * deploy - concerns that must run at the edge (they need the claiming {@link RepositoryFormat} and the post-hash,
 * pre-layout moment), which is why they live here rather than as a store {@link PublishInterceptor}.
 *
 * <p>Each hook is a no-op by default, so an implementation overrides only the points it cares about. The three points
 * bracket the {@code ACCEPT}/{@code QUARANTINE}/{@code REJECT} choreography the edge already runs:
 * <ul>
 *   <li>{@link #beforeLayout} runs on {@code ACCEPT}, after the screen chain assigned the blob's {@code hash} but
 *       <em>before</em> the format lays it out. A present {@link Refusal} short-circuits: the edge answers it and lays
 *       nothing out and fires no {@code published()} - the seam the enterprise edge fires its release-immutability
 *       {@code 409} from.</li>
 *   <li>{@link #held} runs on the {@code QUARANTINE} branch, around the {@code 202}, so the enterprise edge records the
 *       quarantine-dispatch replay context for the held body.</li>
 *   <li>{@link #verdict} runs once per screened write with the chain's final disposition, for the enterprise deploy
 *       observation/metric.</li>
 * </ul>
 */
public interface EdgeHooks {

    /** The no-op default the free edition binds: every hook declines, so the shared edge behaves exactly as if no
     *  edition plugged in. */
    EdgeHooks NONE = new EdgeHooks() {
    };

    /** An edge refusal a {@link #beforeLayout} hook returns to short-circuit a write it will not admit: the HTTP
     *  {@code status} to answer and a human-readable {@code message}. The enterprise edge returns a {@code 409} from
     *  here when a write would overwrite an immutable release. A {@code null} message answers with an empty body. */
    record Refusal(int status, String message) {
    }

    /** Called on {@code ACCEPT} after the screen chain stored the body under {@code hash} but before the
     *  {@link RepositoryFormat} lays it out. Returning a {@link Refusal} short-circuits the write (the edge answers the
     *  refusal, lays nothing out and fires no {@code published()}); {@link Optional#empty()} lets the layout proceed.
     *  No-op (accept) by default. */
    default Optional<Refusal> beforeLayout(RepositoryFormat format, ArtifactStore store, ArtifactDescriptor descriptor,
                                           String hash, FormatExchange exchange) throws IOException {
        return Optional.empty();
    }

    /** Called on the {@code QUARANTINE} branch (the body is stored for review, not laid out), around the edge's
     *  {@code 202}, so an edition can record the held body's replay context - {@code path} is the request path and
     *  {@code hash} the stored blob. No-op by default. */
    default void held(RepositoryFormat format, ArtifactStore store, String path, String hash, FormatExchange exchange)
            throws IOException {
    }

    /** Called once per screened write with the chain's final {@code disposition} and the (hash-enriched) descriptor,
     *  for an edition's deploy observation or metric. Fires whatever the disposition, including when a
     *  {@link #beforeLayout} refusal short-circuited an otherwise-{@code ACCEPT} write. No-op by default. */
    default void verdict(PublishInterceptor.Disposition disposition, ArtifactDescriptor descriptor,
                         FormatExchange exchange) throws IOException {
    }
}
