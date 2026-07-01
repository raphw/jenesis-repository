package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Adapts a servlet request and response to the framework-neutral {@link FormatExchange} a {@link
 * build.jenesis.repository.format.RepositoryFormat} speaks, so a Spring MVC controller dispatches to the format
 * plugins through the same contract every dispatcher uses. The path a format sees is supplied to the constructor and
 * returned from {@link #path()} unchanged, so a caller passes either the full request path or one with a routing
 * prefix already stripped.
 */
public final class ServletFormatExchange implements FormatExchange {

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final String path;

    public ServletFormatExchange(HttpServletRequest request, HttpServletResponse response, String path) {
        this.request = request;
        this.response = response;
        this.path = path;
    }

    @Override
    public String method() {
        return request.getMethod();
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String requestUri() {
        return request.getRequestURI();
    }

    @Override
    public String queryParameter(String name) {
        return request.getParameter(name);
    }

    @Override
    public String requestHeader(String name) {
        return request.getHeader(name);
    }

    @Override
    public InputStream requestStream() throws IOException {
        return request.getInputStream();
    }

    @Override
    public void setResponseHeader(String name, String value) {
        response.setHeader(name, value);
    }

    @Override
    public OutputStream respond(int status, long contentLength) throws IOException {
        response.setStatus(status);
        if (contentLength > 0) {
            response.setContentLengthLong(contentLength);
        }
        return response.getOutputStream();
    }
}
