package build.jenesis.repository;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The Spring Boot entry point for the dual-layout repository, an additive alternative to the headless
 * {@link RepositoryServer} that runs on the JDK HTTP server. The framework-neutral logic - the dual layout, the
 * {@link Authorization} credential model, the {@link build.jenesis.repository.format.RepositoryFormat} plugins, the
 * {@link ImportJobs} migration - carries over unchanged; this module only assembles it behind Spring MVC
 * ({@link RepositoryController}), wires the beans ({@link RepositoryConfig}) and gates the wire with Spring Security
 * ({@link SecurityConfig}). The storage backend is selected by {@code jenesis.repository.store} through
 * {@code ArtifactStoreProvider} (ServiceLoader, filesystem fallback).
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
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RepositoryApplication.class)
                .run("--server.port=" + port);
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
