package build.jenesis.repository.server;

import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.store.TenantsProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryAutoConfiguration.class);

    public RepositoryAutoConfiguration(Environment environment) {
        // Hand the Spring Environment to the config-driven SPI enable/disable convention before any bean below
        // discovers providers, so every jenesis.repository.* toggle - including its JENESIS_REPOSITORY_* environment
        // spelling through relaxed binding - gates ServiceLoader discovery deployment-wide.
        Features.configure(environment::getProperty);
    }

    @Bean
    @ConditionalOnMissingBean
    public ArtifactStore artifactStore(RepositoryProperties properties, Environment environment) {
        ArtifactStore store = ArtifactStoreProvider.resolve(properties.getStore(), environment::getProperty);
        long quota = properties.quotaBytes();
        ArtifactStore quotaed = quota > 0 ? new QuotaArtifactStore(store, quota) : store;
        // Read-only is the outermost wrapper, so every write - through the quota meter or straight to the backend, at
        // an HTTP endpoint or an internal path - is refused at this one choke point before it reaches the delegate.
        return properties.isReadOnly() ? new ReadOnlyArtifactStore(quotaed) : quotaed;
    }

    @Bean
    @ConditionalOnMissingBean
    public Authorization authorization(RepositoryProperties properties, ArtifactStore store) {
        if (!properties.isAuth()) {
            // Secure-defaults principle: an insecure configuration must be loud, not silent. Per-credential
            // authorization is on by default; this deployment turned it off explicitly (jenesis.repository.auth=false),
            // so warn at boot that every request is served with no credential. Anonymous is a legitimate explicit
            // choice, so this warns rather than failing the boot.
            LOGGER.warn("SECURITY: per-credential authorization is DISABLED (jenesis.repository.auth=false) - the "
                    + "repository is running ANONYMOUS/OPEN and every request is served without a credential. This is "
                    + "an explicit opt-out; unset it or set jenesis.repository.auth=true (the default) to enforce "
                    + "authorization.");
        }
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
        // A parallel SPI: every discovered format is active unless configured off by name
        // (jenesis.repository.<format>=false), so the one image carries every format and a deployment trims by config.
        List<RepositoryFormat> formats = new ArrayList<>();
        ServiceLoader.load(RepositoryFormat.class).forEach(format -> {
            if (Features.active(format.name(), format.requiredConfig())) {
                formats.add(format);
            }
        });
        return formats;
    }

    @Bean
    @ConditionalOnMissingBean(name = "importSourceProviders")
    public List<ImportSourceProvider> importSourceProviders() {
        List<ImportSourceProvider> providers = new ArrayList<>();
        ServiceLoader.load(ImportSourceProvider.class).forEach(provider -> {
            if (Features.active(provider.name(), provider.requiredConfig())) {
                providers.add(provider);
            }
        });
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
                                             ProxyFormat.Fetcher fetcher, ObservationRegistry observations) {
        return new FormatDispatcher(formats, upstreams, fetcher, observations);
    }

    @Bean
    @ConditionalOnMissingBean
    public LoggingObservationHandler loggingObservationHandler() {
        // The one logging pillar of the Observation API, registered once beside the Observations wrapper so every
        // jenesis.* operation logs from a single handler. Boot attaches it to the auto-configured ObservationRegistry.
        return new LoggingObservationHandler();
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
    @ConditionalOnMissingBean
    public RoutedServing routedServing() {
        // No per-repository routing in the free single-tenant edition: every repository serves over its own hosted
        // store. A distribution that offers proxy/group repositories contributes its own RoutedServing bean (backed
        // by its router), which this @ConditionalOnMissingBean default backs off behind.
        return RoutedServing.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public BatchIngestion batchIngestion(RepositoryProperties properties) {
        // Off by default; the archive-explode feature is a deployment opt-in, its entry cap the zip-bomb bound.
        return new BatchIngestion(properties::isBatchUpload, properties::getBatchUploadMaxEntries);
    }

    @Bean(initMethod = "start")
    @ConditionalOnMissingBean
    public DemoSeeding demoSeeding(@Qualifier("formats") List<RepositoryFormat> formats,
                                   ProxyFormat.Fetcher fetcher,
                                   ArtifactStore store,
                                   RepositoryProperties properties) {
        // Demo mode seeds a fresh, empty repository with real artifacts through the formats' own pull-through paths -
        // a background walk after boot, never blocking it, and only against a completely empty artifact space; off by
        // default. It targets the configured fixed-tenant space (root.scope(tenant).scope(repository)), the same
        // space FixedTenantRouting resolves reads to.
        ArtifactStore scoped = store.scope(properties.getTenant()).scope(properties.getRepository());
        // A read-only deployment runs no background job that mutates the store - the seed writes, so it is disabled
        // here rather than left to fail against the read-only store choke point on its worker thread.
        return new DemoSeeding(properties.isDemo() && !properties.isReadOnly(),
                new DemoSeeder(formats, fetcher), scoped, () -> {
        });
    }

    @Bean
    @ConditionalOnMissingBean(name = "repositoryController")
    public RepositoryController repositoryController(RepositoryRouting routing,
                                                     FormatDispatcher dispatcher,
                                                     List<ImportSourceProvider> importSources,
                                                     ProxyFormat.Fetcher fetcher,
                                                     BatchIngestion batch,
                                                     ArtifactStore store,
                                                     RoutedServing routed,
                                                     Environment environment) {
        // A format reads a runtime toggle off the exchange (the Maven metadata computation opt-in); resolve the bare
        // setting key against the environment under the shared jenesis.repository.* prefix, into which a stored
        // setting is layered at boot, so the format needs no settings dependency. The un-scoped store is handed in so
        // the /api/assets enumeration can scope to an explicitly named repo within the request's tenant. The routed
        // serving seam (NONE here, a router in a multi-repository distribution) drives a read of a proxy/group repo.
        return new RepositoryController(routing, dispatcher, importSources, fetcher, batch,
                key -> environment.getProperty("jenesis.repository." + key), store, routed);
    }
}
