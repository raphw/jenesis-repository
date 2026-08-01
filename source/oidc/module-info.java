/**
 * The OIDC token exchange as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.spi.TokenExchangeProvider} answering to {@code oidc}, validating a workload's
 * id-token against the tenant's trust policy with Spring Security's per-issuer decoders (OIDC discovery, JWKS
 * signature verification, key rotation) and minting a short-lived credential on a match. The exchange endpoint
 * discovers it with {@code ServiceLoader}; a deployment without this module answers 501 there - and carries none
 * of the OAuth2/JOSE dependency stack, which lives here rather than in the server.
 *
 * @jenesis.release 25
 *
 */
module build.jenesis.repository.oidc {
    requires build.jenesis.repository.server.spi;
    requires spring.security.oauth2.core;
    requires spring.security.oauth2.jose;
    exports build.jenesis.repository.oidc to build.jenesis.repository.test;
    provides build.jenesis.repository.server.spi.TokenExchangeProvider
            with build.jenesis.repository.oidc.OidcExchangeProvider;
}
