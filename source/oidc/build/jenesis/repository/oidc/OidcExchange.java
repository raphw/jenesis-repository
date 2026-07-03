package build.jenesis.repository.oidc;

import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.server.TokenExchange;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Exchanges a workload's OIDC id-token for a short-lived Jenesis credential, so a CI job authenticates with the token
 * its platform already issues - no static secret to store or leak. The token is validated against the tenant's trust
 * policy ({@link Authorization#trusts}): the issuer must name a configured trust, and the token must pass a per-issuer
 * decoder built by Spring Security's {@link JwtDecoders#fromIssuerLocation} - which performs OIDC discovery, verifies
 * the signature against the issuer's published JWKS (with caching and key rotation) and checks the issuer and expiry.
 * The decoder is the vetted Spring/Nimbus one, not hand-rolled crypto; only the trust's audience and subject (a glob)
 * matching stays here, since no library knows a deployment's trust store. On a match a new key is minted carrying the
 * trust's grant and expiring after the trust's ttl. An issuer is honoured only when a trust already names it, so a
 * forged or foreign token mints nothing.
 */
public final class OidcExchange implements TokenExchange {

    private final Authorization authorization;
    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    public OidcExchange(Authorization authorization) {
        this.authorization = authorization;
    }

    @Override
    public Exchanged exchange(String tenant, String token) throws IOException {
        if (token == null || token.isBlank()) {
            return null;
        }
        for (Authorization.Trust trust : authorization.trusts(tenant)) {
            Jwt jwt;
            try {
                jwt = decoders.computeIfAbsent(trust.issuer(), JwtDecoders::fromIssuerLocation).decode(token);
            } catch (RuntimeException e) {
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
