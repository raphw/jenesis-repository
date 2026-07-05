package build.jenesis.repository.format;

import module java.base;

/**
 * A framework-neutral view of one HTTP request and its response, so a {@link RepositoryFormat} can speak its
 * protocol (read the method, path, query and headers; stream the body; set the status and response headers)
 * without binding to the JDK HTTP server of the skeleton or the servlet stack of the production build. Each
 * dispatcher adapts its own exchange to this interface. The {@code contentLength} of {@link #respond} follows the
 * JDK convention: a positive value is the exact body length, {@code 0} streams an unknown length (chunked), and a
 * negative value sends no body.
 */
public interface FormatExchange {

    String method();

    /** The request path, with any repository prefix already stripped (so a format sees {@code /maven/...}, {@code /v2/...}). */
    String path();

    /**
     * The full external request path, including any repository prefix the dispatcher stripped from {@link #path()}.
     * A format builds absolute self-referential URLs (an npm tarball, say) from this so they keep the {@code /<repo>/}
     * segment under multi-tenant routing; on the single-repository headless server it is the same as {@link #path()}.
     */
    default String requestUri() {
        return path();
    }

    String queryParameter(String name);

    String requestHeader(String name);

    /**
     * A named server-configuration value the format reads to honour a runtime toggle, or {@code null} when unset -
     * the seam through which a format consults a deployment setting without binding to any settings layer. The key is
     * the bare setting name (e.g. {@code maven-metadata-compute}); the dispatcher that built the exchange resolves it
     * from the deployment's effective configuration. The {@code default} returns {@code null}, so a format sees the
     * shipped default on any exchange that carries no configuration (a headless embed, an internal push exchange, a
     * test double); the servlet dispatcher overrides it to answer from the Spring environment, into which an
     * operator's stored setting is layered.
     */
    default String setting(String key) {
        return null;
    }

    InputStream requestStream() throws IOException;

    void setResponseHeader(String name, String value);

    OutputStream respond(int status, long contentLength) throws IOException;

    default void respond(int status, byte[] content) throws IOException {
        try (OutputStream out = respond(status, content.length == 0 ? -1 : content.length)) {
            if (content.length > 0) {
                out.write(content);
            }
        }
    }

    default void respond(int status) throws IOException {
        respond(status, -1L).close();
    }
}
