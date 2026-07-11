package build.jenesis.repository.server;

import build.jenesis.repository.store.Features;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A named factory for a {@link TokenExchange}, discovered at runtime with {@link ServiceLoader} - so the machinery
 * that validates workload identity tokens (OIDC discovery, JWKS signature checks) is a drop-in module with its own
 * dependencies, and the composition names no protocol. Each provider reads its own configuration through the
 * {@code config} lookup (a property accessor returning {@code null} when unset). With no module installed,
 * {@link #resolve} answers {@link TokenExchange#NONE}: the exchange endpoint says the feature is not installed.
 */
public interface TokenExchangeProvider {

    /** The exchange name this provider answers to, e.g. {@code oidc}. */
    String name();

    /** Build the exchange over the deployment's {@link Authorization} (whose trust policy admits tokens), reading
     *  settings through {@code config}; empty when off. */
    Optional<TokenExchange> create(Authorization authorization, UnaryOperator<String> config);

    /** The config keys this exchange cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The first enabled exchange discovered via {@link ServiceLoader} (an exclusive SPI: an explicit
     *  {@code jenesis.repository.token-exchange=<name>} selects one by name, a {@code jenesis.repository.<name>=false}
     *  skips one, {@link Features}), or {@link TokenExchange#NONE} when none answers. */
    static TokenExchange resolve(Authorization authorization, UnaryOperator<String> config) {
        Optional<String> selection = Features.selection("token-exchange");
        for (TokenExchangeProvider provider : ServiceLoader.load(TokenExchangeProvider.class)) {
            if (selection.isPresent()
                    ? !provider.name().equalsIgnoreCase(selection.get())
                    : !Features.active(provider.name(), provider.requiredConfig())) {
                continue;
            }
            Optional<TokenExchange> exchange = provider.create(authorization, config);
            if (exchange.isPresent()) {
                return exchange.get();
            }
        }
        return TokenExchange.NONE;
    }
}
