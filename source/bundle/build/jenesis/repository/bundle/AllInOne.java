package build.jenesis.repository.bundle;

import build.jenesis.repository.server.RepositoryApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the repository server off the all-in-one module path. The only difference to launching
 * {@link RepositoryApplication} directly is the config name: with the server and the console both in this bundle,
 * two modules carry a root {@code application.properties}, so this launcher reads the bundle's own
 * {@code allinone.properties} (a copy of the server's configuration) and stays deterministic. Every capability on
 * the module path runs until configured off - {@code jenesis.repository.<feature>=false} degrades an implementation
 * exactly like a missing module, {@code jenesis.repository.<spi>=<feature>} selects among exclusive ones - so the
 * image this launcher fronts is trimmed with {@code docker run -e}, never rebuilt.
 */
public final class AllInOne {

    private AllInOne() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(RepositoryApplication.class)
                .properties("spring.config.name=allinone")
                .run(args);
    }

    /**
     * Boot the all-in-one server on the given port ({@code 0} picks an ephemeral one) and return a handle exposing
     * the bound port and closing the context, so a test can drive the exact composition the image runs over HTTP.
     * The port rides as an argument, not a property: {@code allinone.properties} pins {@code server.port=${PORT:8080}}
     * and config files outrank default properties, so a property-passed {@code 0} would silently bind 8080.
     */
    public static Running start(int port) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RepositoryApplication.class)
                .properties("spring.config.name=allinone")
                .run("--server.port=" + port);
        return new Running(Integer.parseInt(context.getEnvironment().getProperty("local.server.port")), context);
    }

    /** A handle on a started server: the actually bound port and an orderly shutdown, without leaking Spring types. */
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
