package build.jenesis.repository.server;

import build.jenesis.repository.format.ProxyFormat;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.Map;

/**
 * The Spring Boot entry point for the dual-layout repository. The framework-neutral logic - the dual layout, the
 * {@link Authorization} credential model, the {@link build.jenesis.repository.format.RepositoryFormat} plugins, the
 * pull-through {@link PullThroughCache} proxy and the {@link ImportJobs} migration - is plain domain code; this
 * module only assembles it behind Spring MVC ({@link RepositoryController}), wires the beans ({@link RepositoryConfig})
 * and gates the wire with Spring Security ({@link SecurityConfig}). The storage backend is selected by
 * {@code jenesis.repository.store} through {@code ArtifactStoreProvider} (ServiceLoader, filesystem fallback).
 */
@SpringBootApplication
public class RepositoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepositoryApplication.class, args);
    }

    /**
     * Boot the server on the given port ({@code 0} picks an ephemeral one) and return a handle exposing the bound
     * port and closing the context, so an embedder or test can drive the real server over HTTP without the Spring
     * types leaking into its module.
     */
    public static Running start(int port) {
        return start(port, Map.of(), null);
    }

    /**
     * Boot the server proxying a local miss to the given upstreams (format name to upstream URI) over the default
     * HTTP {@link PullThroughCache#http() fetcher}, for a programmatic boot that hits a real upstream.
     */
    public static Running start(int port, Map<String, URI> upstreams) {
        return start(port, upstreams, null);
    }

    /**
     * Boot the server proxying a local miss to the given upstreams (format name to upstream URI) through the given
     * {@link ProxyFormat.Fetcher} (the default HTTP fetch when {@code null}). The upstreams and fetcher are
     * registered as the {@code upstreams} and {@code fetcher} beans before refresh, overriding the property-driven
     * defaults in {@link RepositoryConfig}, so a test can supply a fixed upstream and a counting or fake fetcher.
     */
    public static Running start(int port, Map<String, URI> upstreams, ProxyFormat.Fetcher fetcher) {
        Map<String, URI> copy = Map.copyOf(upstreams);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(RepositoryApplication.class);
        if (!copy.isEmpty() || fetcher != null) {
            builder.initializers((ApplicationContextInitializer<ConfigurableApplicationContext>) context -> {
                if (!copy.isEmpty()) {
                    context.getBeanFactory().registerSingleton("upstreams", copy);
                }
                if (fetcher != null) {
                    context.getBeanFactory().registerSingleton("fetcher", fetcher);
                }
            });
        }
        ConfigurableApplicationContext context = builder.run("--server.port=" + port);
        int bound = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return new Running(bound, context);
    }

    /**
     * A handle on a started server: its actually bound port (so a caller can start on an ephemeral port 0 and
     * discover it) and an orderly shutdown. Keeps the Spring {@link ConfigurableApplicationContext} private so a
     * caller - a test, say - need not require any Spring module to drive the server over HTTP.
     */
    public static final class Running implements AutoCloseable {

        private final int port;
        private final ConfigurableApplicationContext context;

        private Running(int port, ConfigurableApplicationContext context) {
            this.port = port;
            this.context = context;
        }

        public int port() {
            return port;
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
