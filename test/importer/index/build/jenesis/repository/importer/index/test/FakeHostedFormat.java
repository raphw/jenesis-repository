package build.jenesis.repository.importer.index.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;

/** A fake hosted-only format (no {@code ProxyFormat}), so a test proves the connector refuses a format that
 *  cannot enumerate an upstream. */
public final class FakeHostedFormat implements RepositoryFormat {

    @Override
    public String name() {
        return "hosted";
    }

    @Override
    public boolean handles(String path) {
        return false;
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        exchange.respond(404);
    }
}
