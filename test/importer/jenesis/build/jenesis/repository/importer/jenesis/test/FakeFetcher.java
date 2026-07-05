package build.jenesis.repository.importer.jenesis.test;

import build.jenesis.repository.format.ProxyFormat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fixed in-memory {@link ProxyFormat.Fetcher}: it answers each URL from a canned response map (an unmapped URL is
 * a transport failure) and records the request headers of every call, so a test can assert the walk carried the API
 * key. The default {@code download} materializes a stream from the same map, so both the listing fetch and the asset
 * download are served without a network.
 */
final class FakeFetcher implements ProxyFormat.Fetcher {

    private final Map<String, ProxyFormat.Fetched> responses;
    final List<Map<String, String>> requests = new ArrayList<>();

    FakeFetcher(Map<String, ProxyFormat.Fetched> responses) {
        this.responses = responses;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
        requests.add(requestHeaders);
        return Optional.ofNullable(responses.get(url.toString()));
    }
}
