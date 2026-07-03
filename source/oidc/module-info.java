/**
 * The OIDC token exchange as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.TokenExchangeProvider} answering to {@code oidc}, validating a workload's
 * id-token against the tenant's trust policy with Spring Security's per-issuer decoders (OIDC discovery, JWKS
 * signature verification, key rotation) and minting a short-lived credential on a match. The exchange endpoint
 * discovers it with {@code ServiceLoader}; a deployment without this module answers 501 there - and carries none
 * of the OAuth2/JOSE dependency stack, which lives here rather than in the server.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.nimbusds/nimbus-jose-jwt 10.9 SHA-256/64d613d91140bad0dab8f0c41960f919ec8705a9ced9418146598b4b3ae71349
 * @jenesis.pin org.springframework.security/spring-security-oauth2-core 7.1.0 SHA-256/68c6bfbace2a429cdd277ce848f8a1a6ea8e33bb386fa2ba19636821457c376f
 * @jenesis.pin org.springframework.security/spring-security-oauth2-jose 7.1.0 SHA-256/a1620a4424e40035dc33d3a53d98a9e978a96d98334a43aaef0bbd60268d0f8c
 * @jenesis.pin spring.security.oauth2.core 7.1.0
 * @jenesis.pin spring.security.oauth2.jose 7.1.0
 */
module build.jenesis.repository.oidc {
    requires build.jenesis.repository.server;
    requires spring.security.oauth2.core;
    requires spring.security.oauth2.jose;
    exports build.jenesis.repository.oidc to build.jenesis.repository.test;
    provides build.jenesis.repository.server.TokenExchangeProvider
            with build.jenesis.repository.oidc.OidcExchangeProvider;
}
