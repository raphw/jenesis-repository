package build.jenesis.repository.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The configuration of the Spring repository server, bound from {@code jenesis.repository.*}: the artifact-store
 * backend name ({@code filesystem} by default, chosen through {@code ArtifactStoreProvider}), the fixed
 * {@link #getTenant() tenant} / {@link #getRepository() repository} artifact space every request resolves to
 * (each {@code default} by default, the {@link FixedTenantRouting} specialization of the shared
 * {@code <tenant>/<repository>/...} store layout), whether the wire is
 * gated by the {@link Authorization} credential model (anonymous by default, the headless deployment), an
 * optional repository-wide storage {@link #getQuota() quota}, and the pull-through {@link #getProxy() proxy}
 * upstreams keyed by format name ({@code jenesis.repository.proxy.<format>}), so a format that is a
 * {@link build.jenesis.repository.format.ProxyFormat} serves a local miss from the upstream.
 */
@ConfigurationProperties(prefix = "jenesis.repository")
public class RepositoryProperties {

    private String store = "filesystem";

    private String tenant = "default";

    private String repository = "default";

    private boolean auth = false;

    private String quota = "";

    private long rateLimit = 0;

    private Map<String, String> proxy = new LinkedHashMap<>();

    private Duration proxyMissTtl = Duration.ofSeconds(60);

    private boolean batchUpload = false;

    private int batchUploadMaxEntries = 10_000;

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    /** The tenant of the fixed artifact space this deployment serves; a multi-tenant routing ignores it and reads
     *  the tenant from the request instead. */
    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    /** The repository of the fixed artifact space this deployment serves; a multi-tenant routing ignores it and
     *  reads the repository from the request path instead. */
    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
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

    /** How long an upstream {@code 404} is remembered so a build tool's repeated probes for an artifact that is not
     *  there (a version range, a missing SNAPSHOT, an optional classifier) are answered from memory rather than
     *  re-hitting the upstream every time; {@code 0} disables the negative cache. */
    public Duration getProxyMissTtl() {
        return proxyMissTtl;
    }

    public void setProxyMissTtl(Duration proxyMissTtl) {
        this.proxyMissTtl = proxyMissTtl;
    }

    /** Whether a publish request carrying the {@code X-Jenesis-Explode} header is walked as an archive and exploded
     *  into a per-entry publish through {@link BatchIngestion}; off by default, so the header is inert and an archive
     *  is stored verbatim as one artifact unless a deployment opts in. */
    public boolean isBatchUpload() {
        return batchUpload;
    }

    public void setBatchUpload(boolean batchUpload) {
        this.batchUpload = batchUpload;
    }

    /** The ceiling on how many members one exploded archive may publish - the zip-bomb axis that matters, since every
     *  entry streams and its size is irrelevant; a walk stops at the cap and reports it in the manifest. */
    public int getBatchUploadMaxEntries() {
        return batchUploadMaxEntries;
    }

    public void setBatchUploadMaxEntries(int batchUploadMaxEntries) {
        this.batchUploadMaxEntries = batchUploadMaxEntries;
    }
}
