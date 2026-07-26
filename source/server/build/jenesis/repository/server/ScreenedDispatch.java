package build.jenesis.repository.server;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The ingress write edge for the free repository: it runs the discovered {@link build.jenesis.repository.store.PublishInterceptor}
 * screen chain over a claimed single-body write <em>before</em> the format lays it out, so screening lives at the edge
 * and a {@link RepositoryFormat} is a pure layout writer. It is the free-core mirror of the enterprise deploy edge's
 * screen/layout/observe choreography, sharing the same {@link Publication#screen} + restream + {@link Publication#published}
 * split, so the two editions converge on one write choreography (the enterprise deploy edge is retired onto this seam
 * in a later step).
 *
 * <p>For a {@code PUT}/{@code POST}/{@code PATCH} claimed by a {@link RepositoryFormat#screened() screened} format the
 * edge:
 * <ul>
 *   <li>{@link Publication#screen screens} the request body: it is stored content-addressed and the interceptor chain
 *       runs once over its {@link ArtifactDescriptor} - {@link ArtifactLayout#describe the format's layout descriptor}
 *       when it has one, else a bare {@link ArtifactDescriptor#at coordinate-less descriptor};</li>
 *   <li>on {@code ACCEPT} restreams the stored {@code blobs/<hash>} into {@link RepositoryFormat#handle}, which lays the
 *       bytes out in its namespace and writes the response, then fires {@link Publication#published} with the descriptor
 *       enriched with the blob's hash and size so an after-commit observer gets the accepted artifact's identity;</li>
 *   <li>on {@code QUARANTINE} answers {@code 202} (stored for review, not laid out);</li>
 *   <li>on {@code REJECT} answers {@code 422} (nothing laid out; the orphan blob is left for garbage collection).</li>
 * </ul>
 * A write claimed by an {@link RepositoryFormat#screened() unscreened} format (OCI, whose multi-request protocol carries
 * no single body to screen) and every non-body verb ({@code GET}, {@code HEAD}, {@code DELETE}) dispatch through the
 * normal {@link FormatDispatcher} untouched, exactly as before - so the format-level pull-through and delete paths are
 * unchanged.
 *
 * <p>With the free edition's empty discovered chain {@code screen} degrades to a plain store-then-restream and an
 * accepted {@code PUT} is byte-for-byte what a direct dispatch produced (the same content-addressed blob, the same
 * pointer the format links, the same response). This edge is nonetheless load-bearing for the enterprise fixed-tenancy
 * mode ({@code jenesis.repository.tenancy=fixed}), where writes fall through this free controller with the full
 * {@code ComplianceScreen} chain discovered - so the choreography must be exactly the enterprise deploy edge's.
 */
public final class ScreenedDispatch {

    private final FormatDispatcher dispatcher;
    private final EdgeHooks hooks;

    /** The free edge: no edition plugged in, so the {@link EdgeHooks#NONE no-op hooks} run and the choreography is the
     *  documented free one. The convenience arm the free {@link RepositoryController} and the existing tests use. */
    public ScreenedDispatch(FormatDispatcher dispatcher) {
        this(dispatcher, EdgeHooks.NONE);
    }

    /** As above, with an edition's {@link EdgeHooks} threaded in: the enterprise edge binds its tenant/immutability/
     *  quarantine/observation concerns here while sharing this one screening choreography rather than forking it. */
    public ScreenedDispatch(FormatDispatcher dispatcher, EdgeHooks hooks) {
        this.dispatcher = dispatcher;
        this.hooks = hooks;
    }

    /**
     * Screen and lay out (or quarantine/reject) a claimed single-body write; otherwise dispatch through the normal
     * {@link FormatDispatcher}. Returns {@code true} when a format claimed the path (the caller has its response), or
     * {@code false} when none did, so the caller answers a {@code 404} - the same contract as
     * {@link FormatDispatcher#dispatch}.
     */
    public boolean dispatch(FormatExchange exchange, ArtifactStore store) throws IOException {
        Optional<RepositoryFormat> owner = dispatcher.owner(exchange.path());
        if (owner.isEmpty()) {
            return false;
        }
        RepositoryFormat format = owner.get();
        if (isSingleBodyWrite(exchange.method()) && format.screened()) {
            screen(format, exchange, store);
            return true;
        }
        return dispatcher.dispatch(exchange, store);
    }

    /** Store-and-screen the body once at the edge, then route by the chain's verdict: lay out on {@code ACCEPT},
     *  {@code 202} on {@code QUARANTINE}, {@code 422} on {@code REJECT}. A write never proxies (the pull-through cache
     *  only serves reads), so on {@code ACCEPT} the format's {@link RepositoryFormat#handle} is driven directly over
     *  the restreamed blob rather than through the dispatcher's proxy branch - the same effect a direct dispatch of a
     *  write has, now with the body already screened. */
    private void screen(RepositoryFormat format, FormatExchange exchange, ArtifactStore store) throws IOException {
        ArtifactDescriptor descriptor = describe(format, exchange.path());
        Publication.Published outcome = new Publication(store).screen(descriptor, exchange.requestStream());
        switch (outcome.disposition()) {
            case ACCEPT -> {
                String hash = outcome.hash();
                // The edge plug-in seam runs post-hash but pre-layout: a present Refusal short-circuits (the enterprise
                // edge's release-immutability 409), so nothing is laid out and no published() fires. With the free
                // no-op hooks this is always empty and the accepted body lays out exactly as before.
                Optional<EdgeHooks.Refusal> refusal = hooks.beforeLayout(format, store, descriptor, hash, exchange);
                if (refusal.isPresent()) {
                    EdgeHooks.Refusal refused = refusal.get();
                    exchange.respond(refused.status(), refused.message() == null
                            ? new byte[0] : refused.message().getBytes(StandardCharsets.UTF_8));
                } else {
                    format.handle(new RestreamExchange(exchange, () -> store.open("blobs/" + hash)), store);
                    // Enrich the descriptor with the accepted blob's identity (as Publication.route() does inline) so
                    // the after-commit observers ride the edge-screened publish with the hash and size, not just the path.
                    new Publication(store).published(descriptor.withBlob(hash, store.size("blobs/" + hash)));
                }
            }
            case QUARANTINE -> {
                // The held branch: the body is stored for review, not laid out. An edition records its replay context
                // around the 202 (the enterprise QuarantineDispatch record); the free no-op hook does nothing.
                hooks.held(format, store, exchange.path(), outcome.hash(), exchange);
                exchange.respond(202);
            }
            case REJECT -> exchange.respond(422);
        }
        // One verdict per screened write for an edition's deploy observation/metric; a no-op for the free edition.
        hooks.verdict(outcome.disposition(), descriptor.withBlob(outcome.hash(), store.size("blobs/" + outcome.hash())), exchange);
    }

    /** The claiming format's layout descriptor for the path when it has one (so an observer keys on the neutral
     *  ecosystem/coordinate/version), else a bare descriptor carrying only the format name and path - the same shape
     *  the enterprise deploy edge builds ({@code ArtifactDescriptor.at(plugin.name(), path)}). */
    private static ArtifactDescriptor describe(RepositoryFormat format, String path) {
        if (format instanceof ArtifactLayout layout) {
            Optional<ArtifactDescriptor> described = layout.describe(path);
            if (described.isPresent()) {
                return described.get();
            }
        }
        return ArtifactDescriptor.at(format.name(), path);
    }

    /** A single-body publish verb - the write that carries one artifact body for the edge to screen. A
     *  {@code DELETE} (and any other verb) has no body to screen and dispatches normally. */
    private static boolean isSingleBodyWrite(String method) {
        return "PUT".equals(method) || "POST".equals(method) || "PATCH".equals(method);
    }
}
