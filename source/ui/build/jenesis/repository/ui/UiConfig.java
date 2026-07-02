package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Bridges the framework-neutral console primitives into Spring: the {@link Panel} plugins (discovered with
 * {@code ServiceLoader}, exactly as the repository server discovers its formats), the {@link ArtifactStore} the panels
 * read (the same backend the server writes, selected by name through {@code ArtifactStoreProvider}), and the
 * {@link Principals} authority model. Each bean is {@link ConditionalOnMissingBean conditional}, so an enterprise
 * console that contributes its own store, panel set or authority model overrides the default and this backs off.
 */
@Configuration(proxyBeanMethods = false)
public class UiConfig {

    @Bean
    @ConditionalOnMissingBean(name = "panels")
    public List<Panel> panels() {
        List<Panel> panels = new ArrayList<>();
        ServiceLoader.load(Panel.class).forEach(panels::add);
        return panels;
    }

    @Bean
    @ConditionalOnMissingBean
    public ArtifactStore artifactStore(UiProperties properties, Environment environment) {
        return ArtifactStoreProvider.resolve(properties.getStore(), environment::getProperty);
    }

    @Bean
    @ConditionalOnMissingBean
    public Principals principals(UiProperties properties) {
        return new Principals(properties);
    }
}
