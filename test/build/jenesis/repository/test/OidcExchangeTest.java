package build.jenesis.repository.test;

import build.jenesis.repository.Authorization;
import build.jenesis.repository.OidcExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OIDC exchange end to end against a real signature: a temporary RSA key signs a JWT, the public key is served as
 * a JWKS over a local HTTP server, and the exchange is driven for the success, tampered-signature, expired,
 * wrong-issuer and unmatched-subject cases - so the verification path (discovery, JWKS, RS256, claim matching) is
 * exercised, not stubbed.
 */
class OidcExchangeTest {

    @TempDir
    Path root;

    private Authorization authorization;
    private OidcExchange exchange;
    private HttpServer server;
    private String issuer;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
        exchange = new OidcExchange(authorization);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration",
                respond("{\"jwks_uri\":\"" + issuer + "/jwks\"}"));
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        server.createContext("/jwks", respond("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"n\":\""
                + unsigned(publicKey.getModulus()) + "\",\"e\":\"" + unsigned(publicKey.getPublicExponent()) + "\"}]}"));
        server.start();

        authorization.setTrust("acme", new Authorization.Trust("github", issuer, "jenesis",
                "repo:acme/app:*", "releases", "repository:read,repository:write", Duration.ofMinutes(15)));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void a_valid_token_is_exchanged_for_a_short_lived_key_carrying_the_trusts_grant() throws IOException {
        OidcExchange.Exchanged exchanged = exchange.exchange("acme",
                jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300)));
        assertThat(exchanged).isNotNull();
        assertThat(exchanged.trust()).isEqualTo("github");
        assertThat(exchanged.expires()).isAfter(Instant.now()).isBefore(Instant.now().plus(Duration.ofMinutes(16)));
        assertThat(authorization.authorize(exchanged.key(), "releases", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void a_tampered_signature_is_rejected() throws IOException {
        String token = jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        assertThat(exchange.exchange("acme", tampered)).as("a forged signature mints nothing").isNull();
    }

    @Test
    void an_expired_token_a_foreign_audience_and_a_wrong_subject_are_all_rejected() throws IOException {
        assertThat(exchange.exchange("acme",
                jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().minusSeconds(300))))
                .as("expired").isNull();
        assertThat(exchange.exchange("acme",
                jwt("someone-else", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300))))
                .as("wrong audience").isNull();
        assertThat(exchange.exchange("acme",
                jwt("jenesis", "repo:evil/app:ref:refs/heads/main", Instant.now().plusSeconds(300))))
                .as("subject outside the pattern").isNull();
    }

    @Test
    void a_tenant_with_no_trust_for_the_issuer_exchanges_nothing() throws IOException {
        assertThat(exchange.exchange("globex",
                jwt("jenesis", "repo:acme/app:ref:refs/heads/main", Instant.now().plusSeconds(300))))
                .as("no trust for this tenant").isNull();
    }

    private String jwt(String audience, String subject, Instant expiry) {
        String header = base64("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}");
        String payload = base64("{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience + "\",\"sub\":\"" + subject
                + "\",\"exp\":" + expiry.getEpochSecond() + "}");
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static com.sun.net.httpserver.HttpHandler respond(String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }
}
