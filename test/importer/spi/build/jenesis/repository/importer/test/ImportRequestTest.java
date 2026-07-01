package build.jenesis.repository.importer.test;

import build.jenesis.repository.importer.ImportRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import request value type: the two-argument constructor sets only {@code url} and {@code repository} and leaves
 * the optional fields null, and each {@code with...} produces an independent copy that changes one field while carrying
 * the others forward, so a provider composes a fully-configured request without mutating the original.
 */
class ImportRequestTest {

    private final URI url = URI.create("https://incumbent.example/");

    @Test
    void the_required_fields_are_set_and_the_optional_ones_default_to_null() {
        ImportRequest request = new ImportRequest(url, "libs-release");
        assertThat(request.url()).isEqualTo(url);
        assertThat(request.repository()).isEqualTo("libs-release");
        assertThat(request.format()).isNull();
        assertThat(request.username()).isNull();
        assertThat(request.password()).isNull();
        assertThat(request.cursor()).isNull();
    }

    @Test
    void each_with_returns_an_independent_copy_changing_one_field() {
        ImportRequest base = new ImportRequest(url, "libs-release");

        ImportRequest formatted = base.withFormat("maven");
        assertThat(formatted.format()).isEqualTo("maven");
        assertThat(base.format()).as("the original is untouched").isNull();

        ImportRequest credentialed = formatted.withCredentials("user", "secret");
        assertThat(credentialed.username()).isEqualTo("user");
        assertThat(credentialed.password()).isEqualTo("secret");
        assertThat(credentialed.format()).as("earlier fields carry forward").isEqualTo("maven");

        ImportRequest resumed = credentialed.withCursor("page-2");
        assertThat(resumed.cursor()).isEqualTo("page-2");
        assertThat(resumed.url()).isEqualTo(url);
        assertThat(resumed.repository()).isEqualTo("libs-release");
        assertThat(resumed.format()).isEqualTo("maven");
        assertThat(resumed.username()).isEqualTo("user");
        assertThat(resumed.password()).isEqualTo("secret");
    }
}
