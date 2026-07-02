package build.jenesis.repository.store;

import module java.base;

/**
 * A format-neutral description of one artifact at a request path: the ecosystem-canonical coordinate a format parsed
 * out of its own layout, plus the content-addressed identity {@link Publication} assigns it once the blob is stored.
 * Formats emit it; upload post-processors (a compliance gate, quarantine audit, inventory, download tracking) and
 * neutral cleanup consume it, so no code outside a format ever re-parses a layout. The {@code ecosystem} /
 * {@code coordinate} / {@code version} triple is the neutral identity every such concern keys on.
 */
public record ArtifactDescriptor(String ecosystem,
                                 String coordinate,
                                 String version,
                                 String path,
                                 String contentType,
                                 boolean prerelease,
                                 String hash,
                                 long size) {

    /** A descriptor for a path that carries no coordinate - a checksum, a raw file, a generated index: the owning
     *  ecosystem and the path, with no coordinate, version, content type, prerelease flag or blob identity. */
    public static ArtifactDescriptor at(String ecosystem, String path) {
        return new ArtifactDescriptor(ecosystem, null, null, path, null, false, null, -1L);
    }

    /** The same descriptor with the content-addressed identity {@link Publication} assigns once the blob is stored -
     *  the SHA-256 it landed under ({@code blobs/<hash>}) and its stored byte length - which is what an interceptor
     *  sees. */
    ArtifactDescriptor withBlob(String hash, long size) {
        return new ArtifactDescriptor(ecosystem, coordinate, version, path, contentType, prerelease, hash, size);
    }
}
