package build.jenesis.repository.test;

import build.jenesis.repository.server.spi.ImportEdgeProvider;
import build.jenesis.repository.store.Features;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WFE.1 - the free-core {@link ImportEdgeProvider} discovery seam, unit half. Proves the single question the free
 * {@link build.jenesis.repository.server.RepositoryAutoConfiguration} asks - "is a distribution's import edge
 * installed?" - answers off the shared {@code Features} enable/disable convention: {@link TestImportEdgeProvider} is
 * discovered via {@link java.util.ServiceLoader} (this test module's {@code provides} clause) but stays inert until its
 * required-config activation key is set, so the free import edge is served by default and yields only when a
 * distribution is genuinely configured for. The end-to-end consequence - the free mapping is then not registered - is
 * proven against the live server by {@link ImportEdgeYieldTest}.
 */
class ImportEdgeProviderTest {

    @Test
    void no_provider_is_installed_by_default_so_the_free_import_edge_is_served() {
        // The default lookup (system properties / environment) with the activation key unset: the discovered test
        // provider self-disables on its missing required config, so no import edge is claimed.
        System.clearProperty(TestImportEdgeProvider.ACTIVATION_KEY);
        Features.reset();
        try {
            assertThat(ImportEdgeProvider.installed())
                    .as("a discovered-but-inert provider does not claim the import edge - the free edge is served")
                    .isFalse();
        } finally {
            Features.reset();
        }
    }

    @Test
    void a_provider_is_installed_once_its_required_config_is_set() {
        System.setProperty(TestImportEdgeProvider.ACTIVATION_KEY, "true");
        Features.reset();
        try {
            assertThat(ImportEdgeProvider.installed())
                    .as("an active provider claims the import edge, so the free controller yields")
                    .isTrue();
        } finally {
            System.clearProperty(TestImportEdgeProvider.ACTIVATION_KEY);
            Features.reset();
        }
    }

    @Test
    void an_explicit_feature_off_switch_falls_back_to_the_free_import_edge() {
        // Even with the required config present, jenesis.repository.<name>=false disables the provider (the shared
        // Features switch), so a deployment can fall back to the free import edge without removing the module.
        System.setProperty(TestImportEdgeProvider.ACTIVATION_KEY, "true");
        System.setProperty("jenesis.repository.test-import-edge", "false");
        Features.reset();
        try {
            assertThat(ImportEdgeProvider.installed())
                    .as("an explicitly-disabled provider does not claim the edge")
                    .isFalse();
        } finally {
            System.clearProperty(TestImportEdgeProvider.ACTIVATION_KEY);
            System.clearProperty("jenesis.repository.test-import-edge");
            Features.reset();
        }
    }
}
