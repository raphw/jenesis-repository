package build.jenesis.repository.importer.jenesis;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;

/**
 * Discovers the jenesis-to-jenesis import source: {@code handles("jenesis")} (the default name match) and builds a
 * {@link JenesisSource} over the request's base URL and repository. Reports a format per asset, so - like the Nexus
 * connector and unlike Artifactory - it needs no up-front format ({@code requiresFormat()} stays {@code false}). The
 * jenesis API key, when present, is taken from the request's password (falling back to its username), since jenesis
 * auth is a single opaque key rather than a username/password pair.
 */
public final class JenesisSourceProvider implements ImportSourceProvider {

    @Override
    public String name() {
        return "jenesis";
    }

    @Override
    public String label() {
        return "Jenesis";
    }

    @Override
    public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
        JenesisSource source = new JenesisSource(request.url(), request.repository(), fetcher);
        String key = request.password() != null ? request.password() : request.username();
        if (key != null) {
            source = source.withKey(key);
        }
        return request.cursor() == null ? source : source.from(request.cursor());
    }
}
