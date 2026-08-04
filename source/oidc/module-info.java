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
 * @jenesis.pin com.nimbusds/nimbus-jose-jwt 10.9 SHA-256/64d613d91140bad0dab8f0c41960f919ec8705a9ced9418146598b4b3ae71349
 * @jenesis.pin commons-logging/commons-logging 1.3.5 SHA-256/6d7a744e4027649fbb50895df9497d109f98c766a637062fe8d2eabbb3140ba4
 * @jenesis.pin io.micrometer/micrometer-commons 1.17.0 SHA-256/03919dc71e2417ec4b5c254c4ba924963c972e124190f73cdcb68ed51c6eede6
 * @jenesis.pin io.micrometer/micrometer-observation 1.17.0 SHA-256/2fc95a327578d3b2a81c3ff40e646a4a21e46b0153ccbbf91690142bf80d9661
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 * @jenesis.pin org.springframework.security/spring-security-core 7.1.0 SHA-256/f8cecce9e65db9fe9ea42ca92b04d6e4e4320ff9d492aa60b753716ea397262c
 * @jenesis.pin org.springframework.security/spring-security-crypto 7.1.0 SHA-256/6f6957548a28451712e53b94a3e77057735b2fcec04c99ca6dd555b574453a98
 * @jenesis.pin org.springframework.security/spring-security-oauth2-core 7.1.0 SHA-256/68c6bfbace2a429cdd277ce848f8a1a6ea8e33bb386fa2ba19636821457c376f
 * @jenesis.pin org.springframework.security/spring-security-oauth2-jose 7.1.0 SHA-256/a1620a4424e40035dc33d3a53d98a9e978a96d98334a43aaef0bbd60268d0f8c
 * @jenesis.pin org.springframework/spring-aop 7.0.8 SHA-256/1178f039e087884174e2affc46e484f4a8bd7f2a4e011d33dd9137709f740f80
 * @jenesis.pin org.springframework/spring-beans 7.0.8 SHA-256/6ec2e361a8872a71d8b1ff66f1bcb8cfa29fcc437931998919da7cecfb59b45b
 * @jenesis.pin org.springframework/spring-context 7.0.8 SHA-256/1eb7d552414ebac00e30ab3e809138d810785f6d2c4271db77cdf0181f308f19
 * @jenesis.pin org.springframework/spring-core 7.0.8 SHA-256/726ba2a5130833644bdf267a55ff26e1f52e8dcc9aa1ffa06904ca9c14619f25
 * @jenesis.pin org.springframework/spring-expression 7.0.8 SHA-256/3c97c38ab59c77ee886e08ccf8096f6bb58a1245f68dfed7a40e93f41c435f9a
 * @jenesis.pin org.springframework/spring-web 7.0.8 SHA-256/4d4ed7ecb0453d25d735ea27d025ea36b003c3d29cb7d006bedd6d5188a2f5c0
 * @jenesis.pin spring.security.oauth2.core 7.1.0
 * @jenesis.pin spring.security.oauth2.jose 7.1.0
 */
module build.jenesis.repository.oidc {
    requires build.jenesis.repository.server.spi;
    requires spring.security.oauth2.core;
    requires spring.security.oauth2.jose;
    exports build.jenesis.repository.oidc to build.jenesis.repository.test;
    provides build.jenesis.repository.server.spi.TokenExchangeProvider
            with build.jenesis.repository.oidc.OidcExchangeProvider;
}
