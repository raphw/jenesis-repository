package build.jenesis.repository.proxy.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.HttpFetcherProvider;
import build.jenesis.repository.proxy.NegativeCachingFetcher;
import build.jenesis.repository.proxy.RevalidatingFetcher;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link HttpFetcherProvider} composes the HTTP fetcher with index revalidation always on and the negative miss
 * cache installed when {@code proxy-miss-ttl} parses to a positive window. This covers the hand-rolled duration
 * parser - ISO-8601 ({@code PT90S}) and the simple style Spring binds ({@code 90s}, {@code 5m}, {@code 500ms}), a
 * blank value defaulting to a minute, and an unknown suffix throwing - and the {@code 0} branch that disables the
 * negative cache entirely, so {@code create} returns the plain revalidating fetcher with no miss cache wrapped around
 * it. The parsed window is only observable through which fetcher {@code create} composes, so the assertions read that
 * composition: a positive window installs the {@link NegativeCachingFetcher}, {@code 0} does not.
 */
class HttpFetcherProviderTest {

    private final HttpFetcherProvider provider = new HttpFetcherProvider();

    private ProxyFormat.Fetcher create(String missTtl) {
        return provider.create(key -> "proxy-miss-ttl".equals(key) ? missTtl : null).orElseThrow();
    }

    @Test
    void the_provider_answers_to_http() {
        assertThat(provider.name()).isEqualTo("http");
    }

    @Test
    void a_positive_miss_ttl_in_every_accepted_spelling_installs_the_negative_cache() {
        // Each spelling drives a distinct parse branch: PT90S the ISO path, 90s/5m/500ms the simple-suffix switch,
        // and a blank/absent value the one-minute default - all of which are positive, so all install the miss cache.
        for (String missTtl : List.of("PT90S", "90s", "5m", "500ms", "")) {
            assertThat(create(missTtl))
                    .as("a positive proxy-miss-ttl (\"%s\") parses and installs the negative miss cache", missTtl)
                    .isInstanceOf(NegativeCachingFetcher.class);
        }
        assertThat(create(null)).as("unset behaves as blank - the one-minute default")
                .isInstanceOf(NegativeCachingFetcher.class);
    }

    @Test
    void a_zero_miss_ttl_disables_the_negative_cache() {
        ProxyFormat.Fetcher fetcher = create("0");
        assertThat(fetcher).as("0 disables the negative cache - no NegativeCachingFetcher is wrapped")
                .isNotInstanceOf(NegativeCachingFetcher.class)
                .isInstanceOf(RevalidatingFetcher.class);
    }

    @Test
    void an_unknown_suffix_fails_loudly_rather_than_guessing_a_window() {
        assertThatThrownBy(() -> create("5y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5y");
    }
}
