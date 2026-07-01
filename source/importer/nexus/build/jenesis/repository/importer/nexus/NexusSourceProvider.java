package build.jenesis.repository.importer.nexus;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;

/**
 * Builds a {@link NexusSource} for a {@code "nexus"} migration. Discovered by the server through {@code ServiceLoader}
 * over {@link ImportSourceProvider}, so the server supports Nexus by having this module on the path and knows nothing
 * of it otherwise. Nexus needs no ecosystem format up front (it reports one per asset) and can resume from a cursor.
 */
public final class NexusSourceProvider implements ImportSourceProvider {

    @Override
    public boolean handles(String source) {
        return "nexus".equals(source);
    }

    @Override
    public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
        NexusSource source = new NexusSource(request.url(), request.repository(), fetcher);
        if (request.username() != null && request.password() != null) {
            source = source.withCredentials(request.username(), request.password());
        }
        return request.cursor() == null ? source : source.from(request.cursor());
    }
}
