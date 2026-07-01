package build.jenesis.repository.source.artifactory;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.source.ImportRequest;
import build.jenesis.repository.source.ImportSource;
import build.jenesis.repository.source.ImportSourceProvider;

/**
 * Builds an {@link ArtifactorySource} for an {@code "artifactory"} migration. Discovered by the server through
 * {@code ServiceLoader} over {@link ImportSourceProvider}. An Artifactory repository has a single package type, so the
 * request must carry an ecosystem {@code format}; without one this returns null and the caller reports a bad request.
 */
public final class ArtifactorySourceProvider implements ImportSourceProvider {

    @Override
    public boolean handles(String source) {
        return "artifactory".equals(source);
    }

    @Override
    public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
        if (request.format() == null) {
            return null;
        }
        ArtifactorySource source = new ArtifactorySource(request.url(), request.repository(), request.format(), fetcher);
        return request.username() == null || request.password() == null
                ? source
                : source.withCredentials(request.username(), request.password());
    }
}
