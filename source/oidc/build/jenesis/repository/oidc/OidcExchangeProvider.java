package build.jenesis.repository.oidc;

import java.util.Optional;
import java.util.function.UnaryOperator;
import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.server.TokenExchange;
import build.jenesis.repository.server.TokenExchangeProvider;

/**
 * Discovers the OIDC token exchange: tokens are validated against the tenant's trust policy with Spring Security's
 * vetted per-issuer decoders. There is nothing to configure - which issuers are honoured is per-tenant trust data,
 * not deployment configuration - so installing the module is the switch.
 */
public final class OidcExchangeProvider implements TokenExchangeProvider {

    @Override
    public String name() {
        return "oidc";
    }

    @Override
    public Optional<TokenExchange> create(Authorization authorization, UnaryOperator<String> config) {
        return Optional.of(new OidcExchange(authorization));
    }
}
