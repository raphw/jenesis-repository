package build.jenesis.repository.test;

import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.server.RateLimiter;
import build.jenesis.repository.server.RateLimiterProvider;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.server.TokenExchange;
import build.jenesis.repository.server.TokenExchangeProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The config-driven SPI enable/disable convention against the real installed modules: a format configured off
 * ({@code jenesis.repository.<name>=false}) degrades exactly like a missing module, an exclusive SPI's provider is
 * skippable by its feature toggle and selectable by the SPI's selection key
 * ({@code jenesis.repository.token-exchange=<name>}), and a selection naming an uninstalled implementation resolves
 * to the SPI's {@code NONE} sentinel rather than failing - so one image carries every module and configuration
 * decides what runs.
 */
class FeatureTogglesTest {

    @AfterEach
    void restore() {
        Features.reset();
    }

    @Test
    void a_format_configured_off_degrades_like_a_missing_module() {
        assertThat(RepositoryFormat.installed("maven")).isPresent();
        Features.configure(Map.of("jenesis.repository.maven", "false")::get);
        assertThat(RepositoryFormat.installed("maven")).isEmpty();
        assertThat(RepositoryFormat.installed("raw")).isPresent();
    }

    @Test
    void a_token_exchange_configured_off_resolves_to_none() {
        assertThat(TokenExchangeProvider.resolve(Authorization.anonymous(), key -> null))
                .isNotSameAs(TokenExchange.NONE);
        Features.configure(Map.of("jenesis.repository.oidc", "false")::get);
        assertThat(TokenExchangeProvider.resolve(Authorization.anonymous(), key -> null))
                .isSameAs(TokenExchange.NONE);
    }

    @Test
    void an_exclusive_selection_picks_its_implementation_by_name() {
        Features.configure(Map.of("jenesis.repository.token-exchange", "oidc")::get);
        assertThat(TokenExchangeProvider.resolve(Authorization.anonymous(), key -> null))
                .isNotSameAs(TokenExchange.NONE);
    }

    @Test
    void a_selection_naming_an_uninstalled_implementation_resolves_to_none() {
        Features.configure(Map.of("jenesis.repository.token-exchange", "not-installed")::get);
        assertThat(TokenExchangeProvider.resolve(Authorization.anonymous(), key -> null))
                .isSameAs(TokenExchange.NONE);
    }

    @Test
    void a_rate_limiter_configured_off_resolves_to_none() {
        assertThat(RateLimiterProvider.resolve(key -> null)).isNotSameAs(RateLimiter.NONE);
        Features.configure(Map.of("jenesis.repository.token-bucket", "false")::get);
        assertThat(RateLimiterProvider.resolve(key -> null)).isSameAs(RateLimiter.NONE);
    }

    @Test
    void an_import_skips_a_format_configured_off(@TempDir Path root) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        ImportSource source = (consumer, checkpoint) -> {
            consumer.accept("raw", "files/a.txt",
                    () -> new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
            checkpoint.reached(null);
        };
        Features.configure(Map.of("jenesis.repository.raw", "false")::get);
        RepositoryImport.Result withheld = new RepositoryImport().run(source, store);
        assertThat(withheld.imported()).isZero();
        assertThat(withheld.skippedFormats()).containsExactly("raw");
        Features.reset();
        assertThat(new RepositoryImport().run(source, store).imported()).isEqualTo(1);
    }
}
