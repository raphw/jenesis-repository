package build.jenesis.repository.format.test;

import build.jenesis.repository.format.PrivateHosts;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSRF classifier's two hand-rolled ranges the JDK does not recognise: CGNAT ({@code 100.64/10}, RFC 6598) and
 * IPv6 unique-local ({@code fc00::/7}, RFC 4193). Each address is a literal parsed without DNS, so the assertions
 * pin {@link PrivateHosts#isPrivate} exactly at the range boundaries rather than at whatever a resolver returns.
 *
 * <p>The remaining cases pin {@link PrivateHosts#resolvesToPrivate} directly - the host-string SSRF entry point the
 * import trigger and the fetcher's redirect screen actually call - across its three contractual answers: a
 * {@code null}/blank host is not a vector ({@code false}); a host that resolves to any private/loopback/link-local
 * address is refused ({@code true}); and, most importantly, a host that does not resolve at all is <em>not</em>
 * refused ({@code false}) - it is unreachable, so the caller's own attempt fails naturally rather than this screen
 * masking an honest "no such host". Literal IPs are parsed without DNS, so the private/public assertions stay
 * deterministic.
 */
class PrivateHostsTest {

    private static boolean isPrivate(String literal) throws UnknownHostException {
        return PrivateHosts.isPrivate(InetAddress.getByName(literal));
    }

    @Test
    void a_null_or_blank_host_is_not_a_vector() {
        assertThat(PrivateHosts.resolvesToPrivate(null)).as("null host").isFalse();
        assertThat(PrivateHosts.resolvesToPrivate("")).as("empty host").isFalse();
        assertThat(PrivateHosts.resolvesToPrivate("   ")).as("blank host").isFalse();
    }

    @Test
    void a_host_resolving_to_a_private_loopback_or_link_local_address_is_refused() {
        assertThat(PrivateHosts.resolvesToPrivate("127.0.0.1")).as("IPv4 loopback").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("::1")).as("IPv6 loopback").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("169.254.169.254")).as("the link-local cloud metadata service").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("10.0.0.1")).as("a site-local (private) address").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("192.168.1.1")).as("another site-local address").isTrue();
        assertThat(PrivateHosts.resolvesToPrivate("localhost")).as("the name that resolves to loopback").isTrue();
    }

    @Test
    void a_host_resolving_to_a_public_address_is_allowed() {
        assertThat(PrivateHosts.resolvesToPrivate("203.0.113.7")).as("an ordinary public v4 address").isFalse();
        assertThat(PrivateHosts.resolvesToPrivate("2001:4860:4860::8888")).as("a public v6 address").isFalse();
    }

    @Test
    void an_unresolvable_host_is_not_refused_so_the_natural_failure_can_surface() {
        // The javadoc calls this out explicitly: a host that does not resolve is no SSRF vector (nothing to reach), so
        // the screen must pass it through as public rather than refuse it - letting the caller's own connection attempt
        // fail with an honest "no such host" instead of this guard masking it. .invalid is reserved never to resolve.
        assertThat(PrivateHosts.resolvesToPrivate("no-such-host.invalid")).as("an unresolvable host").isFalse();
        assertThat(PrivateHosts.resolvesToPrivate("nothing.here.invalid")).isFalse();
    }

    @Test
    void the_cgnat_range_is_private_at_and_within_its_boundaries() throws UnknownHostException {
        assertThat(isPrivate("100.64.0.0")).as("the low boundary of 100.64/10").isTrue();
        assertThat(isPrivate("100.64.0.1")).isTrue();
        assertThat(isPrivate("100.127.255.254")).isTrue();
        assertThat(isPrivate("100.127.255.255")).as("the high boundary of 100.64/10").isTrue();
    }

    @Test
    void an_address_just_outside_the_cgnat_range_is_public() throws UnknownHostException {
        assertThat(isPrivate("100.63.255.255")).as("just below the CGNAT block").isFalse();
        assertThat(isPrivate("100.128.0.0")).as("just above the CGNAT block").isFalse();
        assertThat(isPrivate("203.0.113.7")).as("an ordinary public v4 address").isFalse();
    }

    @Test
    void the_ipv6_unique_local_range_is_private() throws UnknownHostException {
        assertThat(isPrivate("fc00::1")).as("the fc00::/8 half of fc00::/7").isTrue();
        assertThat(isPrivate("fd00::1")).as("the fd00::/8 half (the locally-assigned form)").isTrue();
        assertThat(isPrivate("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")).isTrue();
    }

    @Test
    void a_public_ipv6_address_is_not_private() throws UnknownHostException {
        assertThat(isPrivate("2001:4860:4860::8888")).as("a public v6 address (Google DNS)").isFalse();
        assertThat(isPrivate("2606:4700:4700::1111")).as("another public v6 address (Cloudflare)").isFalse();
    }
}
