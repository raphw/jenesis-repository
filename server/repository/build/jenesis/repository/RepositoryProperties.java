package build.jenesis.repository;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The configuration of the Spring repository server, bound from {@code jenesis.repository.*}: the artifact-store
 * backend name ({@code filesystem} by default, chosen through {@code ArtifactStoreProvider}), whether the wire is
 * gated by the {@link Authorization} credential model (anonymous by default, the headless free deployment), and the
 * pull-through {@link #getProxy() proxy} upstreams keyed by format name ({@code jenesis.repository.proxy.<format>}),
 * so a format that is a {@link build.jenesis.repository.format.ProxyFormat} serves a local miss from the upstream.
 */
@ConfigurationProperties(prefix = "jenesis.repository")
public class RepositoryProperties {

    private String store = "filesystem";

    private boolean auth = false;

    private Map<String, String> proxy = new LinkedHashMap<>();

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

    public Map<String, String> getProxy() {
        return proxy;
    }

    public void setProxy(Map<String, String> proxy) {
        this.proxy = proxy;
    }
}
