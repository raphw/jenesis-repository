package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;

import module java.base;

/**
 * A {@link FormatExchange} that wraps a real request/response exchange but restreams its request body from an
 * already-stored source instead of the socket, so an ingress edge can hand an accepted blob back to the claiming
 * {@link build.jenesis.repository.format.RepositoryFormat} for pure layout. Everything except the body - the method,
 * path, query, headers, settings, and the whole response side (status, headers, streamed body, range/conditional
 * handling) - delegates to the wrapped exchange, so the format writes its response straight to the original client
 * exactly as it would on a direct dispatch; only {@link #requestStream()} is redirected to the {@link Body} source.
 *
 * <p>This is the free-core, edition-neutral restream exchange the edge screening choreography builds on: after
 * {@link build.jenesis.repository.store.Publication#screen} accepts a body, the edge restreams {@code blobs/<hash>}
 * from the store ({@code () -> store.open("blobs/" + hash)}) into the format through one of these. The body is a
 * restream, never a buffered copy - the {@link Body} opens a fresh {@link InputStream} each time the format asks for
 * one - so a large artifact goes from storage to the format's layout write without being materialised in memory. Both
 * the free {@link RepositoryController} write edge and (once the enterprise deploy edge's private {@code PublishExchange}
 * is retired onto it) the enterprise edge share this one implementation.
 */
public final class RestreamExchange implements FormatExchange {

    /** Opens the request body afresh each time the wrapped format reads it - the seam the edge fills with
     *  {@code () -> store.open("blobs/" + hash)}, so nothing is buffered and a re-read reopens the stored blob. */
    @FunctionalInterface
    public interface Body {
        InputStream open() throws IOException;
    }

    private final FormatExchange delegate;
    private final Body body;

    public RestreamExchange(FormatExchange delegate, Body body) {
        this.delegate = delegate;
        this.body = body;
    }

    @Override
    public String method() {
        return delegate.method();
    }

    @Override
    public String path() {
        return delegate.path();
    }

    @Override
    public String requestUri() {
        return delegate.requestUri();
    }

    @Override
    public String queryParameter(String name) {
        return delegate.queryParameter(name);
    }

    @Override
    public String requestHeader(String name) {
        return delegate.requestHeader(name);
    }

    @Override
    public String setting(String key) {
        return delegate.setting(key);
    }

    @Override
    public InputStream requestStream() throws IOException {
        return body.open();
    }

    @Override
    public void setResponseHeader(String name, String value) {
        delegate.setResponseHeader(name, value);
    }

    @Override
    public OutputStream respond(int status, long contentLength) throws IOException {
        return delegate.respond(status, contentLength);
    }
}
