package build.jenesis.repository.format.test;

import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.Features;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exclusive fetcher SPI seam {@link FetcherProvider#resolve}, driven against three {@link java.util.ServiceLoader}
 * stub providers this module registers ({@code empty} first, then {@code alpha} answering {@code 201} and {@code beta}
 * answering {@code 202}): an explicit {@code jenesis.repository.fetcher=<name>} selection wins, a provider whose
 * {@code create} declines is skipped in favour of the next enabled one, and {@link ProxyFormat.Fetcher#NONE} is
 * returned when every provider is disabled.
 */
class FetcherProviderTest {

    @AfterEach
    void resetFeatures() {
        Features.reset();
    }

    /** A config lookup over a fixed map, so a test controls the {@code jenesis.repository.*} feature toggles the
     *  resolver reads without touching real system properties or the environment. */
    private static UnaryOperator<String> config(Map<String, String> values) {
        return values::get;
    }

    private static int status(ProxyFormat.Fetcher fetcher) throws IOException {
        return fetcher.fetch(URI.create("https://upstream.example/x"), Map.of()).orElseThrow().status();
    }

    @Test
    void an_explicit_selection_picks_that_provider_by_name() throws IOException {
        UnaryOperator<String> beta = config(Map.of("jenesis.repository.fetcher", "beta"));
        Features.configure(beta);
        assertThat(status(FetcherProvider.resolve(beta))).as("beta is selected by name").isEqualTo(202);

        UnaryOperator<String> alpha = config(Map.of("jenesis.repository.fetcher", "alpha"));
        Features.configure(alpha);
        assertThat(status(FetcherProvider.resolve(alpha))).as("alpha is selected by name").isEqualTo(201);
    }

    @Test
    void a_create_empty_provider_is_skipped_and_the_next_enabled_one_wins() throws IOException {
        // No selection and nothing disabled: discovery order is empty, alpha, beta. The empty provider declines
        // (create() is empty), so the resolver must skip it and take alpha - the first that actually builds a fetcher.
        UnaryOperator<String> defaults = config(Map.of());
        Features.configure(defaults);
        assertThat(status(FetcherProvider.resolve(defaults)))
                .as("the declining provider is skipped, the next enabled one wins").isEqualTo(201);
    }

    @Test
    void nothing_enabled_resolves_to_the_none_fetcher() {
        UnaryOperator<String> allOff = config(Map.of(
                "jenesis.repository.empty", "false",
                "jenesis.repository.alpha", "false",
                "jenesis.repository.beta", "false"));
        Features.configure(allOff);
        assertThat(FetcherProvider.resolve(allOff))
                .as("with every provider disabled the resolver answers the NONE singleton by identity")
                .isSameAs(ProxyFormat.Fetcher.NONE);
    }
}
