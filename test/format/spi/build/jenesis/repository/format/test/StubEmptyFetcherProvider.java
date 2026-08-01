package build.jenesis.repository.format.test;

import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;

import module java.base;

/** A stub fetcher provider whose {@code create} always declines (empty): its config never enables it, so
 *  {@link FetcherProvider#resolve} must skip it and continue to the next discovered provider. Declared first in the
 *  service list so the skip-and-continue path is exercised before any provider that builds a fetcher. */
public final class StubEmptyFetcherProvider implements FetcherProvider {

    @Override
    public String name() {
        return "empty";
    }

    @Override
    public Optional<ProxyFormat.Fetcher> create(UnaryOperator<String> config) {
        return Optional.empty();
    }
}
