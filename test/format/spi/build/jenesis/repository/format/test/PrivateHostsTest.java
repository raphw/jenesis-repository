package build.jenesis.repository.format.test;

import build.jenesis.repository.format.PrivateHosts;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSRF classifier's two hand-rolled ranges the JDK does not recognise: CGNAT ({@code 100.64/10}, RFC 6598) and
 * IPv6 unique-local ({@code fc00::/7}, RFC 4193). Each address is a literal parsed without DNS, so the assertions
 * pin {@link PrivateHosts#isPrivate} exactly at the range boundaries rather than at whatever a resolver returns.
 */
class PrivateHostsTest {

    private static boolean isPrivate(String literal) throws UnknownHostException {
        return PrivateHosts.isPrivate(InetAddress.getByName(literal));
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
