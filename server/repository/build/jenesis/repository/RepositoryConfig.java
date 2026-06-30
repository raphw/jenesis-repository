package build.jenesis.repository;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.QuotaArtifactStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Wires the free repository from {@link RepositoryProperties}: the artifact store (a backend chosen by name through
 * {@code ArtifactStoreProvider}), the {@link Authorization} (enforcing when {@code jenesis.repository.auth} is set,
 * otherwise anonymous), the {@link RepositoryFormat} plugins discovered with {@link ServiceLoader}, the pull-through
 * {@code upstreams} (format name to upstream URI, from {@code jenesis.repository.proxy.*}) and the upstream
 * {@link ProxyFormat.Fetcher} (the HTTP fetch by default). Every bean is plain domain code reused as-is; Spring only
 * assembles them. The {@code upstreams} and {@code fetcher} are {@link ConditionalOnMissingBean conditional} so a
 * programmatic boot ({@link RepositoryApplication#start(int, Map, ProxyFormat.Fetcher)}) can register its own, which
 * is how a proxy test supplies a fixed upstream and a counting fetcher. No compliance, staging, cleanup, routing or
 * download tracking - those are enterprise-only.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryProperties.class)
public class RepositoryConfig {

    @Bean
    public ArtifactStore artifactStore(RepositoryProperties properties, Environment environment) {
        ArtifactStore store = ArtifactStoreProvider.resolve(properties.getStore(), environment::getProperty);
        long quota = properties.quotaBytes();
        return quota > 0 ? new QuotaArtifactStore(store, quota) : store;
    }

    @Bean
    public Authorization authorization(RepositoryProperties properties, ArtifactStore store) {
        return properties.isAuth() ? Authorization.enforcing(store) : Authorization.anonymous();
    }

    @Bean
    public List<RepositoryFormat> formats() {
        List<RepositoryFormat> formats = new ArrayList<>();
        ServiceLoader.load(RepositoryFormat.class).forEach(formats::add);
        return formats;
    }

    @Bean
    @ConditionalOnMissingBean(name = "upstreams")
    public Map<String, URI> upstreams(RepositoryProperties properties) {
        Map<String, URI> upstreams = new LinkedHashMap<>();
        properties.getProxy().forEach((format, uri) -> upstreams.put(format, URI.create(uri)));
        return upstreams;
    }

    @Bean
    @ConditionalOnMissingBean(name = "fetcher")
    public ProxyFormat.Fetcher fetcher() {
        return PullThroughCache.http();
    }
}
