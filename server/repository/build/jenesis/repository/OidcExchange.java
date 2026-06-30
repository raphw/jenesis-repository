package build.jenesis.repository;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exchanges a workload's OIDC id-token for a short-lived Jenesis credential, so a CI job authenticates with the token
 * its platform already issues - no static secret to store or leak. The token (a JWT) is validated against the tenant's
 * trust policy ({@link Authorization#trusts}): the issuer must name a configured trust, the RS256 signature must verify
 * against that issuer's published JWKS (discovered at {@code <issuer>/.well-known/openid-configuration} and cached by
 * key id), the token must be unexpired, and its audience and subject (a glob) must match the trust. On a match a new
 * key is minted carrying the trust's grant and expiring after the trust's ttl. Only after the signature verifies are
 * the claims trusted, and an issuer is honoured only when a trust already names it, so a forged or foreign token mints
 * nothing. JSON is read by field extraction rather than a parser, which the flat claim and JWK shapes allow.
 */
public final class OidcExchange {

    /** A freshly exchanged short-lived key, its expiry and the trust that admitted it. */
    public record Exchanged(String key, Instant expires, String trust) {
    }

    private final Authorization authorization;
    private final HttpClient http;
    private final Map<String, Map<String, RSAPublicKey>> keysByIssuer = new ConcurrentHashMap<>();

    public OidcExchange(Authorization authorization) {
        this.authorization = authorization;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Validate {@code token} against {@code tenant}'s trusts and, on a match, mint and return a short-lived
     *  credential; {@code null} when no trust matches or the token fails validation. */
    public Exchanged exchange(String tenant, String token) throws IOException {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String header = decode(parts[0]);
        String payload = decode(parts[1]);
        if (header == null || payload == null || !"RS256".equals(stringField(header, "alg"))) {
            return null;
        }
        String issuer = stringField(payload, "iss");
        if (issuer == null) {
            return null;
        }
        String kid = stringField(header, "kid");
        for (Authorization.Trust trust : authorization.trusts(tenant)) {
            if (!issuer.equals(trust.issuer())) {
                continue;
            }
            RSAPublicKey key = publicKey(issuer, kid);
            if (key == null || !signatureValid(parts, key)) {
                continue;
            }
            long exp = longField(payload, "exp");
            if (exp == 0 || Instant.now().getEpochSecond() > exp + 60) {
                continue;
            }
            if (!audienceMatches(payload, trust.audience()) || !subjectMatches(stringField(payload, "sub"), trust.subject())) {
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

    private boolean signatureValid(String[] parts, RSAPublicKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private RSAPublicKey publicKey(String issuer, String kid) throws IOException {
        Map<String, RSAPublicKey> keys = keysByIssuer.get(issuer);
        if (keys == null || (kid != null && !keys.containsKey(kid))) {
            keys = fetchKeys(issuer);
            keysByIssuer.put(issuer, keys);
        }
        if (kid != null) {
            return keys.get(kid);
        }
        return keys.values().stream().findFirst().orElse(null);
    }

    private Map<String, RSAPublicKey> fetchKeys(String issuer) throws IOException {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        String jwksUri = stringField(get(base + "/.well-known/openid-configuration"), "jwks_uri");
        if (jwksUri == null) {
            return Map.of();
        }
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        for (String entry : keyObjects(get(jwksUri))) {
            if (!"RSA".equals(stringField(entry, "kty"))) {
                continue;
            }
            String modulus = stringField(entry, "n");
            String exponent = stringField(entry, "e");
            if (modulus == null || exponent == null) {
                continue;
            }
            try {
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                        new BigInteger(1, Base64.getUrlDecoder().decode(modulus)),
                        new BigInteger(1, Base64.getUrlDecoder().decode(exponent))));
                String entryKid = stringField(entry, "kid");
                keys.put(entryKid == null ? "" : entryKid, key);
            } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                // skip an unusable key
            }
        }
        return keys;
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

    private static List<String> keyObjects(String jwks) {
        Matcher array = Pattern.compile("\"keys\"\\s*:\\s*\\[(.*)]", Pattern.DOTALL).matcher(jwks);
        if (!array.find()) {
            return List.of();
        }
        List<String> objects = new ArrayList<>();
        for (String part : array.group(1).split("}\\s*,\\s*\\{")) {
            objects.add(part);
        }
        return objects;
    }

    private static boolean audienceMatches(String payload, String audience) {
        if (audience == null || audience.isBlank()) {
            return true;
        }
        String single = stringField(payload, "aud");
        if (single != null) {
            return audience.equals(single);
        }
        Matcher array = Pattern.compile("\"aud\"\\s*:\\s*\\[([^]]*)]").matcher(payload);
        if (array.find()) {
            Matcher value = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(array.group(1));
            while (value.find()) {
                if (audience.equals(value.group(1))) {
                    return true;
                }
            }
        }
        return false;
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

    private static String decode(String base64url) {
        try {
            return new String(Base64.getUrlDecoder().decode(base64url), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\/", "/") : null;
    }

    private static long longField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }
}
