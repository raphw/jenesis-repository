package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.SpiCatalogPanel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SPI catalogue panel renders the repository's plug-in surface grouped by SPI, discovered from the JPMS module
 * graph's {@code provides} declarations. The {@code Panel} contract itself is on this test's module path (the console
 * module provides two - the bundled browse panel and this catalogue panel), so it is a stable fixture: the panel lists
 * that contract by its service type with both implementations grouped under it, and the {@code build.jenesis.}
 * namespace filter keeps the framework's own {@code ServiceLoader} plumbing off the listing.
 */
class SpiCatalogPanelTest {

    @Test
    void it_identifies_itself_as_the_spi_catalogue_panel() {
        SpiCatalogPanel panel = new SpiCatalogPanel();
        assertThat(panel.id()).isEqualTo("spi");
        assertThat(panel.title()).isEqualTo("SPI catalog");
    }

    @Test
    void it_groups_installed_implementations_under_their_spi_from_the_module_graph() {
        // The panel reads no artifact data, so a null store is fine here.
        String body = new SpiCatalogPanel().render(null);

        assertThat(body).as("the panel renders its plug-in-surface intro").contains("SPI");
        // The Panel SPI is on this module path; it groups its two known implementations under the one contract.
        assertThat(body).as("the discovered SPI contract is listed by its service type")
                .contains("build.jenesis.repository.ui.Panel");
        assertThat(body).as("with the two console panels grouped under it")
                .contains("BrowsePanel").contains("SpiCatalogPanel");
        assertThat(body).as("only the product's own SPIs are listed - no framework ServiceLoader noise")
                .doesNotContain("org.springframework").doesNotContain("org.slf4j");
    }
}
