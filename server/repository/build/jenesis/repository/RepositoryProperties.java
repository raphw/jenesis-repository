package build.jenesis.repository;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The configuration of the Spring repository server, bound from {@code jenesis.repository.*}: the artifact-store
 * backend name ({@code filesystem} by default, chosen through {@code ArtifactStoreProvider}) and whether the wire
 * is gated by the {@link Authorization} credential model (anonymous by default, the headless free deployment).
 */
@ConfigurationProperties(prefix = "jenesis.repository")
public class RepositoryProperties {

    private String store = "filesystem";

    private boolean auth = false;

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public boolean isAuth() {
        return auth;
    }

    public void setAuth(boolean auth) {
        this.auth = auth;
    }
}
