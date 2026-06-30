package build.jenesis.repository;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exchanges a workload's OIDC id-token for a short-lived Jenesis credential, so a CI job authenticates with the token
 * its platform already issues - no static secret to store or leak. The token is validated against the tenant's trust
 * policy ({@link Authorization#trusts}): the issuer must name a configured trust, and the token must pass a per-issuer
 * {@link NimbusJwtDecoder} (RS256 only, signature verified against the issuer's discovered JWKS with key rotation and
 * caching, issuer and expiry checked with the usual clock skew). The decoder is the vetted Spring Security/Nimbus one
 * rather than hand-rolled crypto; only the JWKS-uri discovery and the trust's audience and subject (a glob) matching
 * stay here, since no library knows a deployment's trust store. On a match a new key is minted carrying the trust's
 * grant and expiring after the trust's ttl. An issuer is honoured only when a trust already names it, so a forged or
 * foreign token mints nothing.
 */
public final class OidcExchange {

    /** A freshly exchanged short-lived key, its expiry and the trust that admitted it. */
    public record Exchanged(String key, Instant expires, String trust) {
    }

    private final Authorization authorization;
    private final HttpClient http;
    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    public OidcExchange(Authorization authorization) {
        this.authorization = authorization;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Validate {@code token} against {@code tenant}'s trusts and, on a match, mint and return a short-lived
     *  credential; {@code null} when no trust matches or the token fails validation. */
    public Exchanged exchange(String tenant, String token) throws IOException {
        if (token == null || token.isBlank()) {
            return null;
        }
        for (Authorization.Trust trust : authorization.trusts(tenant)) {
            Jwt jwt;
            try {
                jwt = decoder(trust.issuer()).decode(token);
            } catch (JwtException | IOException e) {
                continue;
            }
            if (!audienceMatches(jwt.getAudience(), trust.audience())
                    || !subjectMatches(jwt.getSubject(), trust.subject())) {
                continue;
            }
            String minted = Authorization.mint(tenant);
            String hash = Authorization.hash(minted);
            Instant expires = Instant.now().plus(trust.ttl() == null ? Duration.ofHours(1) : trust.ttl());
            authorization.provision(tenant, hash, "oidc:" + trust.name(), expires);
            authorization.setGrant(tenant, hash, trust.scope(), trust.rights());
            return new Exchanged(minted, expires, trust.name());
        }
        return null;
    }

    private JwtDecoder decoder(String issuer) throws IOException {
        JwtDecoder cached = decoders.get(issuer);
        if (cached != null) {
            return cached;
        }
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        String jwksUri = jwksUri(get(base + "/.well-known/openid-configuration"));
        if (jwksUri == null) {
            throw new IOException("No jwks_uri advertised by " + issuer);
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        decoders.put(issuer, decoder);
        return decoder;
    }

    private String get(String url) throws IOException {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + url, e);
        }
    }

    private static String jwksUri(String discovery) {
        Matcher matcher = Pattern.compile("\"jwks_uri\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(discovery);
        return matcher.find() ? matcher.group(1).replace("\\/", "/") : null;
    }

    private static boolean audienceMatches(List<String> audiences, String required) {
        return required == null || required.isBlank() || audiences.contains(required);
    }

    private static boolean subjectMatches(String subject, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (subject == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        for (String literal : pattern.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        return subject.matches(regex.toString());
    }
}
