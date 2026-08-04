/**
 * The plugin SPI seams the repository server exposes, extracted into their own minimal, framework-neutral module so a
 * plugin that only implements a seam ({@code source/ratelimit}, {@code source/usage}, {@code source/oidc}) requires
 * this contract module rather than the heavy Spring/Tomcat/Boot/Micrometer {@code build.jenesis.repository.server}
 * and inherits none of its closure. It is deliberately {@code java.base}-light - its only dependency beyond
 * {@code java.base} is the equally minimal {@code build.jenesis.repository.store} SPI (for {@code Features} and
 * {@code ArtifactStore}, which {@code Authorization} reads its grants through) - so it carries no Spring, Tomcat,
 * Micrometer or Jackson, exactly the "SPI contract modules stay java.base-light; the heavy deps ride the impl/bundle"
 * rule (&sect;2).
 *
 * <p>It holds the credential model ({@code Authorization}) and the exclusive-selection provider seams the server
 * {@code uses}: the rate limiter ({@code RateLimiter} / {@code RateLimiterProvider}), the credential usage tracker
 * ({@code KeyUsageTracker} / {@code KeyUsageTrackerProvider}), the workload-identity token exchange
 * ({@code TokenExchange} / {@code TokenExchangeProvider}), the {@code /api/capabilities} contributor
 * ({@code CapabilityContributor}) and the import-edge ownership signal ({@code ImportEdgeProvider}). The
 * {@code resolve}/{@code installed} static discovery methods live on the provider types here, so the {@code uses}
 * clauses for them sit in this module; the server {@code requires transitive} this module, so every existing
 * {@code requires build.jenesis.repository.server} consumer still sees the moved types unchanged.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.server.spi {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.server.spi;
    uses build.jenesis.repository.server.spi.RateLimiterProvider;
    uses build.jenesis.repository.server.spi.KeyUsageTrackerProvider;
    uses build.jenesis.repository.server.spi.TokenExchangeProvider;
    uses build.jenesis.repository.server.spi.ImportEdgeProvider;
}
