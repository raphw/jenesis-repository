package build.jenesis.repository.format.test;

import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;

import module java.base;

/** A stub fetcher provider that always builds a fetcher answering every fetch with status {@code 201}, so a test can
 *  tell by the served status which provider {@link FetcherProvider#resolve} picked. */
public final class StubAlphaFetcherProvider implements FetcherProvider {

    @Override
    public String name() {
        return "alpha";
    }

    @Override
    public Optional<ProxyFormat.Fetcher> create(UnaryOperator<String> config) {
        return Optional.of((url, headers) -> Optional.of(new ProxyFormat.Fetched(201, new byte[0], Map.of())));
    }
}
