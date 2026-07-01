package build.jenesis.repository.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The configuration of the Spring repository server, bound from {@code jenesis.repository.*}: the artifact-store
 * backend name ({@code filesystem} by default, chosen through {@code ArtifactStoreProvider}), whether the wire is
 * gated by the {@link Authorization} credential model (anonymous by default, the headless free deployment), an
 * optional repository-wide storage {@link #getQuota() quota}, and the pull-through {@link #getProxy() proxy}
 * upstreams keyed by format name ({@code jenesis.repository.proxy.<format>}), so a format that is a
 * {@link build.jenesis.repository.format.ProxyFormat} serves a local miss from the upstream.
 */
@ConfigurationProperties(prefix = "jenesis.repository")
public class RepositoryProperties {

    private String store = "filesystem";

    private boolean auth = false;

    private String quota = "";

    private long rateLimit = 0;

    private Map<String, String> proxy = new LinkedHashMap<>();

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getQuota() {
        return quota;
    }

    public void setQuota(String quota) {
        this.quota = quota;
    }

    public long getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(long rateLimit) {
        this.rateLimit = rateLimit;
    }

    /** The repository-wide storage ceiling in bytes: a plain count or a number with a {@code K/M/G/T} (1024-based)
     *  suffix; {@code 0} (the default) leaves storage uncapped. */
    public long quotaBytes() {
        String value = quota == null ? "" : quota.trim();
        if (value.isEmpty()) {
            return 0L;
        }
        int split = 0;
        while (split < value.length() && (Character.isDigit(value.charAt(split)) || value.charAt(split) == '.')) {
            split++;
        }
        double number = Double.parseDouble(value.substring(0, split));
        long multiplier = switch (value.substring(split).trim().toUpperCase(Locale.ROOT)) {
            case "", "B" -> 1L;
            case "K", "KB", "KIB" -> 1024L;
            case "M", "MB", "MIB" -> 1024L * 1024;
            case "G", "GB", "GIB" -> 1024L * 1024 * 1024;
            case "T", "TB", "TIB" -> 1024L * 1024 * 1024 * 1024;
            default -> throw new IllegalArgumentException("Unrecognized storage quota unit in: " + value);
        };
        return (long) (number * multiplier);
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
