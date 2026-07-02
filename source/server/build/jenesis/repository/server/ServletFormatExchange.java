package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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

    /**
     * Honour a client {@code Range} request over any artifact a format serves, format-agnostically: a served
     * {@code 200} with a known length advertises {@code Accept-Ranges: bytes}, and a satisfiable {@code Range} is
     * answered {@code 206 Partial Content} with a {@code Content-Range} and only the requested bytes forwarded (the
     * format still writes the whole body; this slices it), so a large-artifact download is resumable. A syntactically
     * valid but out-of-bounds range is a {@code 416}; an unsupported or malformed one is ignored and the full body is
     * served. The format is oblivious - it calls {@code respond(200, length)} either way.
     */
    @Override
    public OutputStream respond(int status, long contentLength) throws IOException {
        if (status == 200 && contentLength >= 0) {
            response.setHeader("Accept-Ranges", "bytes");
            String header = request.getHeader("Range");
            long[] range = header == null ? null : satisfiableRange(header, contentLength);
            if (range == UNSATISFIABLE) {
                response.setStatus(416);
                response.setHeader("Content-Range", "bytes */" + contentLength);
                return OutputStream.nullOutputStream();
            }
            if (range != null) {
                response.setStatus(206);
                response.setHeader("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + contentLength);
                response.setContentLengthLong(range[1] - range[0] + 1);
                return new RangeOutputStream(response.getOutputStream(), range[0], range[1] - range[0] + 1);
            }
        }
        response.setStatus(status);
        if (contentLength > 0) {
            response.setContentLengthLong(contentLength);
        }
        return response.getOutputStream();
    }

    /**
     * Make a buffered {@code 200} conditionally revalidatable, format-agnostically: the response carries an
     * {@code ETag} of its own bytes (a content hash), and a matching {@code If-None-Match} is answered {@code 304 Not
     * Modified} with no body. A format serves its generated indexes and metadata this way (an npm packument, a
     * {@code maven-metadata.xml}, a PyPI index, a Debian {@code Packages}), so a client that polls a mutable index it
     * already has re-downloads it only when it has actually changed - the case conditional requests exist for; a large
     * artifact is streamed (see the range path above), not buffered, so it is unaffected. The {@code ETag} derives
     * from the served bytes, so no format states it and the check never yields a false {@code 304}.
     */
    @Override
    public void respond(int status, byte[] content) throws IOException {
        if (status == 200 && content.length > 0) {
            String etag = etag(content);
            response.setHeader("ETag", etag);
            if (notModified(etag)) {
                response.setStatus(304);
                return;
            }
        }
        try (OutputStream out = respond(status, content.length == 0 ? -1 : content.length)) {
            out.write(content);
        }
    }

    /** Whether the request's {@code If-None-Match} covers this ETag (the strong or weak form, or {@code *}). */
    private boolean notModified(String etag) {
        String header = request.getHeader("If-None-Match");
        if (header == null) {
            return false;
        }
        if (header.trim().equals("*")) {
            return true;
        }
        for (String candidate : header.split(",")) {
            String tag = candidate.trim();
            if (tag.equals(etag) || tag.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    private static String etag(byte[] content) {
        try {
            return '"' + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)) + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final long[] UNSATISFIABLE = new long[0];

    /** Parse a single-range {@code Range: bytes=...} against the content length: {@code [start, end]} inclusive for a
     *  satisfiable range, {@link #UNSATISFIABLE} for a valid-but-out-of-bounds one ({@code 416}), or {@code null} to
     *  ignore (an unsupported unit, a multipart range, or a malformed one - the full body is served). */
    private static long[] satisfiableRange(String header, long length) {
        if (!header.startsWith("bytes=")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (spec.isEmpty() || spec.indexOf(',') >= 0 || dash < 0) {
            return null;
        }
        String from = spec.substring(0, dash).trim();
        String to = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (from.isEmpty()) {
                long suffix = Long.parseLong(to);
                if (suffix <= 0) {
                    return UNSATISFIABLE;
                }
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(from);
                end = to.isEmpty() ? length - 1 : Math.min(Long.parseLong(to), length - 1);
            }
            if (start < 0 || start >= length || start > end) {
                return UNSATISFIABLE;
            }
            return new long[]{start, end};
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** Forwards only a window of the bytes written to it - skipping {@code start}, then passing {@code length} - so a
     *  format that writes a whole artifact serves just the requested range without knowing a range was asked for. */
    private static final class RangeOutputStream extends OutputStream {

        private final OutputStream out;
        private long skip;
        private long remaining;

        private RangeOutputStream(OutputStream out, long start, long length) {
            this.out = out;
            this.skip = start;
            this.remaining = length;
        }

        @Override
        public void write(int b) throws IOException {
            if (skip > 0) {
                skip--;
            } else if (remaining > 0) {
                out.write(b);
                remaining--;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (skip > 0) {
                long skipped = Math.min(skip, length);
                skip -= skipped;
                offset += (int) skipped;
                length -= (int) skipped;
            }
            if (remaining > 0 && length > 0) {
                int written = (int) Math.min(remaining, length);
                out.write(bytes, offset, written);
                remaining -= written;
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
