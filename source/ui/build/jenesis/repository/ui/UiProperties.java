package build.jenesis.repository.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the console, bound from {@code jenesis.ui.*}. The artifact store is selected the same way the
 * repository server selects it - a backend name resolved through {@code ArtifactStoreProvider}, reading its own
 * configuration (root / bucket / connection string) from the environment - so the console reads the very store the
 * server writes. Sign-in is optional: a GitHub OAuth client and a generic OpenID Connect provider (discovered from its
 * issuer URI) are configured here, and when neither is set the app still starts with login disabled. The admins list
 * carries provider-qualified ids ({@code github/<id>}, {@code oidc/<sub>}) that are granted the admin role; when it is
 * empty no signed-in user is an admin (the secure default - the console denies writes until an admin is named), and
 * the single {@code *} wildcard makes every authenticated user an admin (the explicit open-console opt-out).
 *
 *   jenesis.ui.store             the artifact-store backend name (JENESIS_STORE), default filesystem
 *   jenesis.ui.admins            comma-separated provider-qualified admin ids, or * for everyone (JENESIS_UI_ADMINS)
 *   jenesis.ui.github.*          built-in GitHub OAuth client (JENESIS_UI_GITHUB_CLIENT_ID/SECRET)
 *   jenesis.ui.oidc.*            a generic OpenID Connect provider (JENESIS_UI_OIDC_ISSUER_URI/_CLIENT_ID/_CLIENT_SECRET/_NAME)
 */
@ConfigurationProperties(prefix = "jenesis.ui")
public class UiProperties {

    private String store = "filesystem";
    private String admins = "";
    private final Github github = new Github();
    private final Oidc oidc = new Oidc();

    /** GitHub OAuth client credentials; when the client id is blank, GitHub login is disabled. */
    public static class Github {
        private String clientId = "";
        private String clientSecret = "";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }

    /**
     * A generic OpenID Connect provider, configured by its issuer URI (the authorization, token and user-info
     * endpoints and JWK set are discovered). When the issuer or client id is blank, OIDC login is disabled. Members are
     * keyed {@code oidc/<sub>}; {@code name} labels the button.
     */
    public static class Oidc {
        private String issuerUri = "";
        private String clientId = "";
        private String clientSecret = "";
        private String name = "Single sign-on";

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getAdmins() {
        return admins;
    }

    public void setAdmins(String admins) {
        this.admins = admins;
    }

    public Github getGithub() {
        return github;
    }

    public Oidc getOidc() {
        return oidc;
    }
}
