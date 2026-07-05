package build.jenesis.repository.server;

import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.store.TenantsProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Publishes the repository as Spring Boot auto-configuration so a downstream distribution can consume it with a plain
 * {@code requires build.jenesis.repository.server} and extend it by overriding beans rather than forking the module.
 * Every bean is {@link ConditionalOnMissingBean conditional}: the storage backend (a name resolved through
 * {@code ArtifactStoreProvider}), the {@link Authorization} (enforcing when {@code jenesis.repository.auth} is set,
 * otherwise anonymous), the {@link RepositoryFormat} plugins discovered with {@link ServiceLoader}, the pull-through
 * {@code upstreams} (format name to upstream URI, from {@code jenesis.repository.proxy.*}) and upstream
 * {@link ProxyFormat.Fetcher}, the framework-neutral {@link FormatDispatcher}, the {@link RepositoryRouting} (the
 * {@link FixedTenantRouting} default, resolving every request to the configured
 * {@code jenesis.repository.tenant}/{@code jenesis.repository.repository} artifact space), the {@link Tenants}
 * directory (resolved through {@code TenantsProvider}; the fixed single tenant unless a tenants module is
 * discovered), and the {@link RepositoryController} itself. Because an auto-configuration is applied after
 * user configuration, a bean an embedder contributes - an audited or replicating {@link ArtifactStore} decorator, a
 * multi-tenant {@code RepositoryRouting}, a custom controller - wins, and this backs off. Every bean is plain domain
 * code; Spring only assembles it.
 */
@AutoConfiguration
@EnableConfigurationProperties(RepositoryProperties.class)
public class RepositoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ArtifactStore artifactStore(RepositoryProperties properties, Environment environment) {
        ArtifactStore store = ArtifactStoreProvider.resolve(properties.getStore(), environment::getProperty);
        long quota = properties.quotaBytes();
        return quota > 0 ? new QuotaArtifactStore(store, quota) : store;
    }

    @Bean
    @ConditionalOnMissingBean
    public Authorization authorization(RepositoryProperties properties, ArtifactStore store) {
        return properties.isAuth() ? Authorization.enforcing(store) : Authorization.anonymous();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiter rateLimiter() {
        // The metering strategy is a discovered plugin (the token-bucket module); with none installed nothing is
        // limited.
        return RateLimiterProvider.resolve(key -> null);
    }

    @Bean
    @ConditionalOnMissingBean(name = "formats")
    public List<RepositoryFormat> formats() {
        List<RepositoryFormat> formats = new ArrayList<>();
        ServiceLoader.load(RepositoryFormat.class).forEach(formats::add);
        return formats;
    }

    @Bean
    @ConditionalOnMissingBean(name = "importSourceProviders")
    public List<ImportSourceProvider> importSourceProviders() {
        List<ImportSourceProvider> providers = new ArrayList<>();
        ServiceLoader.load(ImportSourceProvider.class).forEach(providers::add);
        return providers;
    }

    @Bean
    @ConditionalOnMissingBean(name = "upstreams")
    public Map<String, URI> upstreams(RepositoryProperties properties) {
        Map<String, URI> upstreams = new LinkedHashMap<>();
        properties.getProxy().forEach((format, uri) -> upstreams.put(format, URI.create(uri)));
        return upstreams;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProxyFormat.Fetcher fetcher(RepositoryProperties properties) {
        // The upstream fetcher is a discovered plugin (the http module); with none installed this resolves to
        // Fetcher.NONE and the deployment serves local content only - no proxying, no imports.
        return FetcherProvider.resolve(key -> "proxy-miss-ttl".equals(key) && properties.getProxyMissTtl() != null
                ? properties.getProxyMissTtl().toString()
                : null);
    }

    @Bean
    @ConditionalOnMissingBean
    public FormatDispatcher formatDispatcher(List<RepositoryFormat> formats,
                                             @Qualifier("upstreams") Map<String, URI> upstreams,
                                             ProxyFormat.Fetcher fetcher) {
        return new FormatDispatcher(formats, upstreams, fetcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public RepositoryRouting repositoryRouting(ArtifactStore store, RepositoryProperties properties) {
        return new FixedTenantRouting(store, properties.getTenant(), properties.getRepository());
    }

    @Bean
    @ConditionalOnMissingBean
    public Tenants tenants(ArtifactStore store, RepositoryProperties properties, Environment environment) {
        // The tenant directory is a discovered plugin (a multi-tenant edition's store-backed module); with none
        // installed the directory is exactly the one configured tenant, and a console gates tenant management on
        // TenantsProvider.installed().
        return TenantsProvider.resolve(store, environment::getProperty, properties.getTenant());
    }

    @Bean
    @ConditionalOnMissingBean(name = "repositoryController")
    public RepositoryController repositoryController(RepositoryRouting routing,
                                                     FormatDispatcher dispatcher,
                                                     List<ImportSourceProvider> importSources,
                                                     ProxyFormat.Fetcher fetcher) {
        return new RepositoryController(routing, dispatcher, importSources, fetcher);
    }
}
