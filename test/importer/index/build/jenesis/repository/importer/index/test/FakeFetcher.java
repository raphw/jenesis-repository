package build.jenesis.repository.importer.index.test;

import build.jenesis.repository.format.ProxyFormat;

import module java.base;

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

    /** The request headers the last request to {@code url} carried, or an empty map when it was never requested - so a
     *  test can assert which requests were (and were not) given a credential. */
    Map<String, String> headersFor(String url) {
        for (int i = urls.size() - 1; i >= 0; i--) {
            if (urls.get(i).equals(url)) {
                return headers.get(i);
            }
        }
        return Map.of();
    }
}
