package build.jenesis.repository.format;

import build.jenesis.repository.store.Features;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A named factory for the upstream {@link ProxyFormat.Fetcher}, discovered at runtime with {@link ServiceLoader} -
 * so the machinery that talks to upstream registries (the HTTP client, index revalidation, negative caching) is a
 * drop-in module, and the dispatcher names no transport. Each provider reads its own configuration through the
 * {@code config} lookup (a property accessor returning {@code null} when unset), staying free of any framework
 * dependency. With no provider installed {@link #resolve} answers {@link ProxyFormat.Fetcher#NONE}: the deployment
 * serves local content only - a proxy upstream is never consulted and an import is refused - which a caller detects
 * by identity rather than by a failing fetch.
 */
public interface FetcherProvider {

    /** The fetcher name this provider answers to, e.g. {@code http}. */
    String name();

    /** Build the fetcher if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<ProxyFormat.Fetcher> create(UnaryOperator<String> config);

    /** The config keys this fetcher cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The first enabled fetcher discovered via {@link ServiceLoader} (an exclusive SPI: an explicit
     *  {@code jenesis.repository.fetcher=<name>} selects one by name, a {@code jenesis.repository.<name>=false}
     *  skips one, {@link Features}), or {@link ProxyFormat.Fetcher#NONE} when none answers. */
    static ProxyFormat.Fetcher resolve(UnaryOperator<String> config) {
        Optional<String> selection = Features.selection("fetcher");
        for (FetcherProvider provider : ServiceLoader.load(FetcherProvider.class)) {
            if (selection.isPresent()
                    ? !provider.name().equalsIgnoreCase(selection.get())
                    : !Features.active(provider.name(), provider.requiredConfig())) {
                continue;
            }
            Optional<ProxyFormat.Fetcher> fetcher = provider.create(config);
            if (fetcher.isPresent()) {
                return fetcher.get();
            }
        }
        return ProxyFormat.Fetcher.NONE;
    }
}
