package build.jenesis.repository.format.oci;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * The OCI manifest choke point (EPIC 26): the one place a manifest write - a {@code docker push} PUT, a pull-through
 * proxy fetch, or an import walk - runs the same {@link PublishInterceptor} screen chain a single-body publish passes,
 * mapped onto OCI's native serving model rather than the {@code publish/} namespace.
 *
 * <p>OCI is EPIC 26's structural exception: it {@link OciFormat#screened() opts out} of the single-body ingress edge
 * ({@code ScreenedDispatch}) because a {@code /v2/} push is multi-request - a session of blob uploads then a manifest
 * that references them by digest - so no single request body reaches that edge, and OCI serves by digest straight from
 * {@code blobs/<hex>} and {@code oci/<name>/tags/<tag>}, never through a {@code publish/<path>} pointer the edge screen
 * gates. This helper is OCI's own manifest-level choke point: it runs {@link Publication#screen} (the very same
 * {@code ComplianceScreen}/inspector chain, since {@code screen} discovers the identical interceptors) over the manifest
 * and maps the verdict onto OCI's native {@code withheld/<hex>} marker - the marker the serving path in
 * {@link OciFormat} already reads on both the blob-serve and manifest-serve paths.
 *
 * <p>{@link Publication#screen} stores the manifest bytes content-addressed at the serving key {@code blobs/<hex>}
 * <em>before</em> the chain runs, so on a non-ACCEPT verdict the marker is load-bearing: without it a rejected manifest
 * would remain pullable by digest straight out of {@code blobs/<hex>}. So ACCEPT lays out OCI's native metadata (the
 * {@code oci/types/<hex>} media-type sidecar, the tag pointer for a tag reference) and clears any stale marker, while
 * QUARANTINE and REJECT write the {@code withheld/<hex>} marker and lay out nothing - a held or rejected manifest then
 * 404s by digest and by tag exactly as a withheld blob does, while its already-uploaded layer blobs stay served raw
 * (layers are out of this choke point's scope, screened bytes by bytes belongs to the manifest that names them).
 *
 * <p>No {@link PublishInterceptor} the free core ships claims {@code /v2/} coordinates today (no {@code QualityInspector}
 * inspects OCI), so with the empty discovered chain every manifest ACCEPTs and this is byte-for-byte the raw write it
 * replaced. The live effect is the choke point itself: a deny-list interceptor now bites an OCI coordinate, the
 * after-commit observers and per-verdict metrics fire on a manifest push, and a future Docker/OCI inspector plugged into
 * the same SPI just works here without touching this format - that is the point of routing every manifest write through
 * one screen rather than three raw writes.
 */
final class OciManifests {

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private OciManifests() {
    }

    /** The outcome of a screened manifest write: the chain's {@link PublishInterceptor.Disposition} and the SHA-256 hex
     *  the manifest was stored under ({@code blobs/<hex>}) - present whatever the verdict, since {@code screen} stores
     *  the bytes content-addressed before it gates. A push maps the disposition to a protocol code; an import and a
     *  proxy have no client response and only need the layout the helper already applied. */
    record Ingested(PublishInterceptor.Disposition disposition, String hex) {
    }

    /**
     * Store the manifest, screen it against its neutral {@code oci} coordinate, and map the verdict onto OCI's native
     * serving model. On {@code ACCEPT} write the media-type sidecar, link the tag pointer (a tag reference only), clear
     * any stale {@code withheld/<hex>} marker and fire the after-commit observers; on {@code QUARANTINE}/{@code REJECT}
     * write the {@code withheld/<hex>} marker the serving path reads and lay out nothing.
     */
    static Ingested ingest(String name, String reference, byte[] content, String mediaTypeOrNull, ArtifactStore store)
            throws IOException {
        String path = "/v2/" + name + "/manifests/" + reference;
        // The neutral descriptor other formats build for the edge: ecosystem oci, coordinate the image name, version
        // the reference, path the request path - what a deny-list interceptor keys on and a metric/observer records.
        ArtifactDescriptor descriptor =
                new ArtifactDescriptor("oci", name, reference, path, mediaTypeOrNull, false, null, -1L);
        // screen() stores the manifest content-addressed at blobs/<hex> (buffering the manifest - small metadata - is
        // fine; a layer blob would never be buffered here) and runs the discovered chain once, without linking a
        // publish/ pointer: OCI owns its own layout below.
        Publication.Published outcome = new Publication(store).screen(descriptor, new ByteArrayInputStream(content));
        String hex = outcome.hash();
        String marker = "withheld/" + hex;
        switch (outcome.disposition()) {
            case ACCEPT -> {
                store.write("oci/types/" + hex, new ByteArrayInputStream(
                        (mediaTypeOrNull == null ? OCI_MANIFEST : mediaTypeOrNull).getBytes(StandardCharsets.UTF_8)));
                if (!reference.startsWith("sha256:")) {
                    OciFormat.linkTag(store, "oci/" + name + "/tags/" + reference, "sha256:" + hex);
                }
                // Clear a stale hold: an identical manifest previously withheld and now accepted (a lifted advisory, a
                // re-push after a rule change) must serve again - the marker is the only thing retracting blobs/<hex>.
                if (store.exists(marker)) {
                    store.delete(marker);
                }
                // The after-commit observers ride the accepted manifest with its blob identity, exactly as the deploy
                // and import edges fire published() once they have laid an accepted artifact out.
                new Publication(store).published(descriptor.withBlob(hex, store.size("blobs/" + hex)));
            }
            case QUARANTINE, REJECT -> {
                // Load-bearing: screen() already stored the bytes at the serving key blobs/<hex>, so without this marker
                // the withheld manifest would be pullable by digest. No sidecar, no tag link - the manifest never serves.
                store.write(marker, new ByteArrayInputStream(
                        outcome.disposition().name().getBytes(StandardCharsets.UTF_8)));
            }
        }
        return new Ingested(outcome.disposition(), hex);
    }
}
