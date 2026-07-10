package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.FormatExchange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An in-memory {@link FormatExchange} for driving a format's {@code handle}/{@code proxy} without an HTTP server: it
 * carries the request method, path, query and headers and a request body, and captures the status, response headers and
 * response body the format writes. The interface's default {@code respond(int)} / {@code respond(int, byte[])} funnel
 * through {@link #respond(int, long)}, and a {@link ByteArrayOutputStream} close is a no-op, so both streamed and
 * buffered responses are captured with no extra work.
 */
final class FakeExchange implements FormatExchange {

    private final String method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> requestHeaders;
    private final byte[] requestBody;
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private int status = -1;

    FakeExchange(String method, String path) {
        this(method, path, new byte[0], Map.of(), Map.of());
    }

    FakeExchange(String method, String path, byte[] requestBody) {
        this(method, path, requestBody, Map.of(), Map.of());
    }

    FakeExchange(String method, String path, byte[] requestBody,
                 Map<String, String> query, Map<String, String> requestHeaders) {
        this.method = method;
        this.path = path;
        this.requestBody = requestBody;
        this.query = query;
        this.requestHeaders = requestHeaders;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String queryParameter(String name) {
        return query.get(name);
    }

    @Override
    public String requestHeader(String name) {
        return requestHeaders.get(name);
    }

    @Override
    public InputStream requestStream() {
        return new ByteArrayInputStream(requestBody);
    }

    @Override
    public void setResponseHeader(String name, String value) {
        responseHeaders.put(name, value);
    }

    @Override
    public OutputStream respond(int status, long contentLength) {
        this.status = status;
        return responseBody;
    }

    int status() {
        return status;
    }

    byte[] responseBytes() {
        return responseBody.toByteArray();
    }

    String responseText() {
        return responseBody.toString(StandardCharsets.UTF_8);
    }

    String responseHeader(String name) {
        return responseHeaders.get(name);
    }

    Map<String, String> responseHeaders() {
        return responseHeaders;
    }
}
