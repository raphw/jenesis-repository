package build.jenesis.repository.format;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The one private-range classifier the SSRF screens share, so the import trigger (an operator-supplied URL) and the
 * {@link ProxyFormat.Fetcher} redirect chain (a 30x {@code Location} an upstream chooses) apply the same rule rather
 * than each carrying its own copy. A host is refused when it resolves to any address an unauthenticated caller must
 * not be able to aim the deployment at: a cloud metadata service ({@code 169.254.169.254}), the loopback control
 * plane ({@code 127.0.0.1}, {@code ::1}), or an internal host on a private, link-local, site-local, multicast,
 * CGNAT or IPv6 unique-local range. A host that does not resolve at all is <em>not</em> refused: it cannot be
 * reached, so it is no SSRF vector, and the caller's own connection attempt then fails naturally rather than this
 * screen masking an honest "no such host".
 */
public final class PrivateHosts {

    private PrivateHosts() {
    }

    /**
     * Whether {@code host} resolves to any address an SSRF screen must refuse. {@code true} when at least one of the
     * host's resolved addresses is {@link #isPrivate private}; {@code false} for a {@code null}/blank host or one
     * that does not resolve (unreachable, so not a vector - a caller lets the natural failure surface). DNS
     * rebinding is out of scope: a caller that follows the fetch after this check races the record, which is why the
     * screen is one guard among the deployment's defaults rather than the only one.
     */
    public static boolean resolvesToPrivate(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException _) {
            return false;
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A private/loopback/wildcard/link-local/site-local/multicast/CGNAT/unique-local address an SSRF screen must not
     * reach. The JDK classifiers cover loopback, the wildcard, link-local ({@code 169.254/16}, {@code fe80::/10}),
     * site-local ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) and multicast; CGNAT ({@code 100.64/10},
     * RFC 6598) and IPv6 unique-local ({@code fc00::/7}, RFC 4193) are checked by hand as the JDK does not recognise
     * them.
     */
    public static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF, second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }
        return (bytes[0] & 0xFE) == 0xFC;
    }
}
