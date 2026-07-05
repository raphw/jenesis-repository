package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.store.TenantsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tenant directory seam in its free-edition shape: no tenants module ships, so {@code installed()} answers
 * {@code false} (the capability signal a console gates tenant management on) and {@code resolve} falls back to the
 * fixed single-tenant directory over the configured tenant - the specialization the shared
 * {@code <tenant>/<repository>/...} layout rests on. A store-backed directory is a provider module's part (the
 * multi-tenant edition's), exercised there; like the publish-interceptor chain, the free edition's discovery is
 * empty by design.
 */
class TenantsProviderTest {

    @TempDir
    Path root;

    @Test
    void the_free_edition_installs_no_tenants_module() {
        assertThat(TenantsProvider.installed()).isFalse();
    }

    @Test
    void resolve_falls_back_to_the_fixed_directory_over_the_configured_tenant() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        Tenants tenants = TenantsProvider.resolve(store, key -> null, "acme");
        assertThat(tenants.list()).containsExactly("acme");
        assertThat(tenants.exists("acme")).isTrue();
        assertThat(tenants.exists("other")).isFalse();
    }

    @Test
    void the_fixed_directory_refuses_to_grow() {
        assertThatThrownBy(() -> Tenants.fixed("default").create("another"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("install a tenants module");
    }

    @Test
    void the_fixed_directory_requires_its_tenant() {
        assertThatNullPointerException().isThrownBy(() -> Tenants.fixed(null));
    }
}
