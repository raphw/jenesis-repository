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
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
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
        logSecurityPosture(environment);
    }

    /** Log the deployment-wide security-posture advisories at boot (WO.5), the single source of truth for the
     *  secure-defaults boot WARNs (auth off, SSRF screen off, dev profile, ...): the same discovered {@link
     *  build.jenesis.repository.posture.SafetyAdvisor} list the console panel and {@code GET /api/posture} surface, so a
     *  condition is expressed once and both logged and shown. A clean deployment logs nothing. */
    private static void logSecurityPosture(Environment environment) {
        build.jenesis.repository.posture.PostureReport report = build.jenesis.repository.posture.PostureReport.discover(
                build.jenesis.repository.posture.Configuration.of(environment::getProperty));
        for (build.jenesis.repository.posture.SecurityAdvisory advisory
                : report.scoped(build.jenesis.repository.posture.Scope.DEPLOYMENT)) {
            String line = "SECURITY POSTURE [" + advisory.severity() + "] " + advisory.id() + ": " + advisory.why()
                    + " Fix: " + advisory.fix() + " (" + advisory.settingKey() + "=" + advisory.settingValue() + ")";
            switch (advisory.severity()) {
                case CRITICAL, WARN -> LOGGER.warn(line);
                case INFO -> LOGGER.info(line);
            }
        }
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
        // Secure-defaults principle: an insecure configuration must be loud, not silent. The auth=false open-deployment
        // WARN is no longer an ad-hoc line here; it is the jenesis.auth.open security-posture advisory
        // (SecurityPosture), logged once at boot by logSecurityPosture(...) and surfaced on the console and
        // GET /api/posture - one source of truth, no divergent second list.
        String anonymousRights = properties.getAnonymousRights().strip();
        if (!properties.isAuth()) {
            // WANON.1 guardrail: anonymous-rights is only meaningful under an enforcing deployment. Under auth=false the
            // instance is ALREADY fully open, so a configured anonymous-rights is redundant and ignored - warn so the
            // operator is not misled into thinking it is narrowing an open deployment.
            if (!anonymousRights.isEmpty()) {
                LOGGER.warn("SECURITY: jenesis.repository.anonymous-rights is set but jenesis.repository.auth=false, so "
                        + "the deployment is ALREADY fully open (every request is served anonymously) and the "
                        + "anonymous-rights grant is redundant and ignored. Set jenesis.repository.auth=true to make it "
                        + "meaningful: keys are then required and a keyless caller is limited to exactly this grant.");
            }
            return Authorization.anonymous();
        }
        // WANON.1 guardrail 2: a loud startup WARN naming exactly what a keyless caller may do, escalated for
        // write/admin. This names the exact grant (the posture surface names the risk, never the value); the
        // jenesis.anonymous.* security-posture advisories carry the governance escalation onto the console and
        // GET /api/posture. Default (empty) => no anonymous access and no warning, byte-for-byte today's behaviour.
        if (!anonymousRights.isEmpty()) {
            if (Authorization.grantsWriteOrAdmin(anonymousRights)) {
                LOGGER.warn("SECURITY: anonymous access ENABLED with WRITE/ADMIN rights: {}. A keyless caller may "
                        + "mutate or administer artifacts with NO credential (a public drop-box / open admin) - the "
                        + "loudest anonymous combination. This is an explicit opt-in; unset "
                        + "jenesis.repository.anonymous-rights to require a key for every request.", anonymousRights);
            } else {
                LOGGER.warn("SECURITY: anonymous access ENABLED: {}. A keyless caller is granted these rights with no "
                        + "credential (the public-mirror pattern - pair with jenesis.repository.read-only=true for a "
                        + "browsable-but-immutable mirror). This is an explicit opt-in; unset "
                        + "jenesis.repository.anonymous-rights to require a key for every request.", anonymousRights);
            }
        }
        return Authorization.enforcing(store).withAnonymousRights(anonymousRights);
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
        properties.getProxy().forEach((format, uri) -> {
            URI upstream = URI.create(uri);
            if (isInsecureUpstream(upstream)) {
                // Secure-defaults principle: an insecure configuration must be loud, not silent - the same stance the
                // authorization bean takes for anonymous mode. A non-HTTPS upstream is proxied verbatim (a build tool
                // pulls its dependencies through it), so a MITM on that hop can inject or tamper with artifacts. Warn
                // loudly at boot rather than refuse: a plaintext internal mirror is a legitimate explicit choice and
                // refusing would break the documented `jenesis.repository.proxy.<format>=<url>` config shape. Point the
                // upstream at an https:// URL to remove this warning.
                LOGGER.warn("SECURITY: the '{}' proxy upstream {} is NOT HTTPS - artifacts are pulled through over a "
                        + "plaintext/untrusted transport and can be tampered with in transit. This is an explicit "
                        + "configuration; use an https:// upstream to secure it.", format, upstream);
            }
            upstreams.put(format, upstream);
        });
        return upstreams;
    }

    /** Whether a proxy upstream is fetched over something other than HTTPS - a plaintext {@code http://}, or a
     *  schemeless or otherwise non-TLS URI - the transport a MITM can tamper with, which {@link #upstreams} warns
     *  about loudly at boot. */
    public static boolean isInsecureUpstream(URI upstream) {
        String scheme = upstream.getScheme();
        return scheme == null || !scheme.equalsIgnoreCase("https");
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

    /** The recent-logs ring (WO.4): a bounded in-memory store of the most recent entries, sized from
     *  {@code jenesis.repository.logs.buffer} at startup - the bound behind {@code GET /api/logs}. */
    @Bean
    @ConditionalOnMissingBean
    public LogRingBuffer logRingBuffer(RepositoryProperties properties) {
        return new LogRingBuffer(properties.getLogsBuffer());
    }

    /** The recent-logs tap (WO.4): attach the logback appender to the running root logger at startup so every entry the
     *  JVM emits is captured into the ring, never re-reading a file. A non-logback slf4j binding leaves the appender
     *  unattached and the ring simply stays empty (graceful). */
    @Bean
    @ConditionalOnMissingBean
    public LogRingAppender logRingAppender(LogRingBuffer buffer) {
        LogRingAppender appender = new LogRingAppender(buffer);
        org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
        if (factory instanceof ch.qos.logback.classic.LoggerContext context) {
            appender.setContext(context);
            appender.start();
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        }
        return appender;
    }

    /** The recent-logs read - {@code GET /api/logs}, reading the same ring the appender feeds. */
    @Bean
    @ConditionalOnMissingBean
    public RecentLogsController recentLogsController(LogRingBuffer buffer) {
        return new RecentLogsController(buffer);
    }

    /** The security-posture read (WO.5) - {@code GET /api/posture}, the console / CLI read of the deployment's
     *  configuration-warning advisories, discovered against the effective {@code Environment}. */
    @Bean
    @ConditionalOnMissingBean
    public PostureController postureController(Environment environment) {
        return new PostureController(environment);
    }

    /** The multi-node consistency check (WCON.2): the fingerprint compare over the shared store, tuned from the
     *  {@code jenesis.consistency.*} settings. Reads only the {@code consistency/nodes/} prefix, never a scan. */
    @Bean
    @ConditionalOnMissingBean
    public NodeConsistency nodeConsistency(ArtifactStore store, Environment environment) {
        return new NodeConsistency(store, NodeConsistency.settingsFrom(environment::getProperty));
    }

    /** This node's fingerprint publisher (WCON.2): a stable node id and a daemon heartbeat that publishes this node's
     *  derived-state fingerprint to the shared store, so the fleet has something to compare. Best-effort - it never
     *  blocks the node it runs on. */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public NodeFingerprintPublisher nodeFingerprintPublisher(NodeConsistency consistency, ArtifactStore store,
                                                             Environment environment) {
        return new NodeFingerprintPublisher(consistency, store, environment::getProperty);
    }

    /** The multi-node consistency read (WCON.2) - {@code GET /api/consistency}, the per-node fingerprints and any
     *  divergence between them, read-authorised like the rest of the wire; the enterprise edition mirrors it as an
     *  operator-gated {@code /api/admin/consistency}. */
    @Bean
    @ConditionalOnMissingBean
    public ConsistencyController consistencyController(NodeConsistency consistency,
                                                       NodeFingerprintPublisher publisher) {
        return new ConsistencyController(consistency, publisher.nodeId());
    }

    /** The observability face of the consistency check (WCON.2, WO.4): live-node and divergence gauges plus a
     *  divergence health check, so the overview shows how many instances there are and whether they agree - what makes
     *  the "these numbers are instance-specific" caveat trustworthy. */
    @Bean
    @ConditionalOnMissingBean
    public NodeConsistencyObservability nodeConsistencyObservability(NodeConsistency consistency) {
        return new NodeConsistencyObservability(consistency);
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

    /**
     * The free single-tenant import edge ({@code POST /repository/admin/import}, {@code GET /repository/admin/import/<id>}),
     * registered as its own controller bean so a richer distribution can OWN the import edge without a cross-layer
     * mapping override (WFE.1). It is registered only when {@link FreeImportEdgeCondition no ImportEdgeProvider is
     * installed}: when a distribution ships an {@link ImportEdgeProvider} - the enterprise edition's tenant-scoped,
     * audited import edge - this bean is not created, so its mapping never joins the handler mapping and the
     * distribution's own controller is the only import edge, retiring the {@code WebMvcRegistrations}
     * mapping-suppression stopgap. With no provider installed (the free product) the edge is served exactly as before.
     * Named so an embedder can still contribute its own {@code importEdgeController} bean and have this back off.
     */
    @Bean
    @ConditionalOnMissingBean(name = "importEdgeController")
    @Conditional(FreeImportEdgeCondition.class)
    public ImportEdgeController importEdgeController(RepositoryRouting routing,
                                                    List<ImportSourceProvider> importSources,
                                                    ProxyFormat.Fetcher fetcher,
                                                    Environment environment) {
        return new ImportEdgeController(routing, importSources, fetcher,
                key -> environment.getProperty("jenesis.repository." + key));
    }

    /**
     * Matches when <em>no</em> {@link ImportEdgeProvider} is installed, so the free {@link ImportEdgeController} is
     * registered only while a richer distribution has not claimed the import edge (WFE.1). Installs the shared
     * {@link Features} lookup against the effective {@link Environment} first, so the same {@code jenesis.repository.*}
     * enable/disable toggles gate the provider discovery here as everywhere else (and a provider missing its required
     * config is inert - the free edge is then served).
     */
    static final class FreeImportEdgeCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Features.configure(context.getEnvironment()::getProperty);
            return !ImportEdgeProvider.installed();
        }
    }
}
