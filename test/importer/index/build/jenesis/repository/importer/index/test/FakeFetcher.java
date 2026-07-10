package build.jenesis.repository.importer.index.test;

import build.jenesis.repository.format.ProxyFormat;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A canned in-memory {@link ProxyFormat.Fetcher}: answers from a fixed URL-to-response map (an unmapped URL is a
 * transport failure) and records every requested URL and its headers, so a test asserts both what a walk fetched
 * and what headers - credentials, say - each request carried. Downloads materialize through the interface's
 * default, so only {@code fetch} is canned.
 */
final class FakeFetcher implements ProxyFormat.Fetcher {

    private final Map<String, ProxyFormat.Fetched> responses = new HashMap<>();

    final List<String> urls = new ArrayList<>();
    final List<Map<String, String>> headers = new ArrayList<>();

    FakeFetcher on(String url, int status, byte[] body) {
        responses.put(url, new ProxyFormat.Fetched(status, body, Map.of()));
        return this;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
        urls.add(url.toString());
        headers.add(Map.copyOf(requestHeaders));
        return Optional.ofNullable(responses.get(url.toString()));
    }
}
