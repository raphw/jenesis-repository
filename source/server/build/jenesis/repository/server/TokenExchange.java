package build.jenesis.repository.server;

import java.io.IOException;
import java.time.Instant;

/**
 * Exchanges a workload's identity token for a short-lived Jenesis credential, so a CI job authenticates with the
 * token its platform already issues - no static secret to store or leak. How a token is validated is the
 * implementation's part, supplied by a {@link TokenExchangeProvider} module discovered with
 * {@link java.util.ServiceLoader} (the OIDC module validates against the tenant's trust policy); with none
 * installed {@link #NONE} stands in and the exchange endpoint says the feature is not installed.
 */
@FunctionalInterface
public interface TokenExchange {

    /** A freshly exchanged short-lived key, its expiry and the trust that admitted it. */
    record Exchanged(String key, Instant expires, String trust) {
    }

    /** The shared exchange standing in when no token-exchange module is installed: it admits nothing. A singleton,
     *  so an endpoint can tell "not installed" by identity and answer 501 rather than 401. */
    TokenExchange NONE = (tenant, token) -> null;

    /** Validate {@code token} against {@code tenant}'s trust policy and, on a match, mint and return a short-lived
     *  credential; {@code null} when no trust matches or the token fails validation. */
    Exchanged exchange(String tenant, String token) throws IOException;
}
