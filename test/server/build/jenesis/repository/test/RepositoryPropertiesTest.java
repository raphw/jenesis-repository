package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryProperties;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The secure default of the repository server's credential model: per-credential authorization is enforced out of the
 * box, and anonymous/open mode is honoured only as an explicit opt-out ({@code jenesis.repository.auth=false}, env
 * {@code JENESIS_REPOSITORY_AUTH=false}). Locks the field default so a fresh deployment never boots open silently.
 * Also pins the hand-rolled storage-quota parser {@link RepositoryProperties#quotaBytes()}: a decimal count with an
 * optional 1024-based {@code K}/{@code M}/{@code G}/{@code T} suffix (each also spelled {@code *B} and {@code *IB}),
 * an unset/blank value meaning uncapped, and an unrecognised unit rejected.
 */
class RepositoryPropertiesTest {

    private static final long KIB = 1024L;
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long TIB = 1024L * 1024 * 1024 * 1024;

    private static long quotaBytes(String quota) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setQuota(quota);
        return properties.quotaBytes();
    }

    @Test
    void per_credential_authorization_is_on_by_default_and_anonymous_is_an_explicit_opt_out() {
        assertThat(new RepositoryProperties().isAuth())
                .as("the secure default: a fresh deployment enforces per-credential authorization").isTrue();

        RepositoryProperties open = new RepositoryProperties();
        open.setAuth(false);
        assertThat(open.isAuth())
                .as("anonymous/open is honoured only as an explicit opt-out (jenesis.repository.auth=false)").isFalse();
    }

    @Test
    void an_unset_or_blank_quota_is_uncapped() {
        assertThat(new RepositoryProperties().quotaBytes()).as("unset (null) is uncapped").isZero();
        assertThat(quotaBytes("")).as("empty is uncapped").isZero();
        assertThat(quotaBytes("   ")).as("blank is uncapped").isZero();
    }

    @Test
    void a_plain_count_is_taken_as_bytes() {
        assertThat(quotaBytes("1024")).isEqualTo(1024L);
        assertThat(quotaBytes("512B")).as("an explicit B suffix is bytes").isEqualTo(512L);
    }

    @Test
    void each_1024_based_suffix_and_its_b_and_ib_spellings_scale() {
        assertThat(quotaBytes("1K")).isEqualTo(KIB);
        assertThat(quotaBytes("1KB")).isEqualTo(KIB);
        assertThat(quotaBytes("1KIB")).isEqualTo(KIB);
        assertThat(quotaBytes("1M")).isEqualTo(MIB);
        assertThat(quotaBytes("1MB")).isEqualTo(MIB);
        assertThat(quotaBytes("1MIB")).isEqualTo(MIB);
        assertThat(quotaBytes("1G")).isEqualTo(GIB);
        assertThat(quotaBytes("1GB")).isEqualTo(GIB);
        assertThat(quotaBytes("1GIB")).isEqualTo(GIB);
        assertThat(quotaBytes("1T")).isEqualTo(TIB);
        assertThat(quotaBytes("1TB")).isEqualTo(TIB);
        assertThat(quotaBytes("1TIB")).isEqualTo(TIB);
    }

    @Test
    void a_lowercase_suffix_is_accepted() {
        assertThat(quotaBytes("2gb")).isEqualTo(2 * GIB);
    }

    @Test
    void a_decimal_value_scales_by_its_suffix() {
        assertThat(quotaBytes("1.5G")).isEqualTo((long) (1.5 * GIB));
    }

    @Test
    void an_unrecognised_unit_is_rejected() {
        assertThatThrownBy(() -> quotaBytes("5X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage quota unit");
    }
}
