package build.jenesis.repository.store.s3.test;

import build.jenesis.repository.store.s3.S3ArtifactStoreProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code s3} endpoint override must be {@code https} by default so credentials and artifact bytes never travel a
 * plaintext transport a MITM can read or tamper with. A plaintext {@code http} endpoint - a local MinIO or LocalStack
 * container - is refused unless {@code JENESIS_AWS_ALLOW_INSECURE_ENDPOINT=true} explicitly opts in. Needs no Docker,
 * so it always runs; the opted-in http path against a real container is exercised by {@code S3ArtifactStoreProviderTest}.
 */
class S3EndpointSchemeTest {

    @Test
    void a_plaintext_endpoint_is_refused_by_default() {
        assertThatThrownBy(() -> S3ArtifactStoreProvider.secureEndpoint("http://localhost:9000", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https")
                .hasMessageContaining("JENESIS_AWS_ALLOW_INSECURE_ENDPOINT");
    }

    @Test
    void the_opt_in_allows_a_plaintext_endpoint() {
        assertThat(S3ArtifactStoreProvider.secureEndpoint("http://localhost:9000", "true"))
                .isEqualTo(URI.create("http://localhost:9000"));
    }

    @Test
    void an_https_endpoint_is_always_accepted() {
        assertThat(S3ArtifactStoreProvider.secureEndpoint("https://s3.example.com", null))
                .isEqualTo(URI.create("https://s3.example.com"));
    }
}
