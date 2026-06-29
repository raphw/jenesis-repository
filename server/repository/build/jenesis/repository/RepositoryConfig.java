package build.jenesis.repository;

import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Wires the free repository from {@link RepositoryProperties}: the artifact store (a backend chosen by name through
 * {@code ArtifactStoreProvider}), the {@link Authorization} (enforcing when {@code jenesis.repository.auth} is set,
 * otherwise anonymous), and the {@link RepositoryFormat} plugins discovered with {@link ServiceLoader}. Every bean
 * is plain domain code reused as-is; Spring only assembles them. No compliance, staging, cleanup, routing or
 * download tracking - those are enterprise-only.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryProperties.class)
public class RepositoryConfig {

    @Bean
    public ArtifactStore artifactStore(RepositoryProperties properties, Environment environment) {
        return ArtifactStoreProvider.resolve(properties.getStore(), environment::getProperty);
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
}
