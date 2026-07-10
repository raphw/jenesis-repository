package build.jenesis.repository.importer.index.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A fake enumerable format, provided by this test module so the connector's {@code installed} lookup discovers it:
 * {@code enumerate} reads a plain-text {@code index} document at the walk's root - one {@code path url} pair per
 * line - and streams a coordinate per line, so a test controls the enumeration through its fetcher fixture. A
 * {@code !boom} line throws once the stream reaches it, standing in for an index that fails mid-walk.
 */
public final class FakeIndexedFormat implements RepositoryFormat, ProxyFormat {

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public boolean handles(String path) {
        return false;
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        exchange.respond(404);
    }

    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, Fetcher fetcher) {
        return false;
    }

    @Override
    public Stream<Coordinate> enumerate(Fetcher fetcher, URI upstream) throws IOException {
        URI index = upstream.resolve("index");
        Fetched fetched = fetcher.fetch(index, Map.of()).orElseThrow(() -> new IOException("No response from " + index));
        if (fetched.status() != 200) {
            throw new IOException("Index fetch failed (" + fetched.status() + ") for " + index);
        }
        return new String(fetched.body(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    if (line.startsWith("!")) {
                        throw new UncheckedIOException(new IOException("the index broke mid-walk"));
                    }
                    int space = line.indexOf(' ');
                    return new Coordinate(line.substring(0, space), URI.create(line.substring(space + 1)));
                });
    }
}
