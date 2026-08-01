package build.jenesis.repository.format.test;

import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;

import module java.base;

/** A stub fetcher provider that always builds a fetcher answering every fetch with status {@code 202}, distinct from
 *  {@link StubAlphaFetcherProvider}'s {@code 201} so a test can tell which provider was selected. */
public final class StubBetaFetcherProvider implements FetcherProvider {

    @Override
    public String name() {
        return "beta";
    }

    @Override
    public Optional<ProxyFormat.Fetcher> create(UnaryOperator<String> config) {
        return Optional.of((url, headers) -> Optional.of(new ProxyFormat.Fetched(202, new byte[0], Map.of())));
    }
}
