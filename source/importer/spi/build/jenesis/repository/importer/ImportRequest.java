package build.jenesis.repository.importer;

import module java.base;

/**
 * The configuration of a submitted migration, handed to an {@link ImportSourceProvider} to build its
 * {@link ImportSource}: the incumbent's {@code url} and the {@code repository} to walk (both required), and the
 * optional bits a given source needs - an ecosystem {@code format} (an Artifactory repository holds one; a Nexus
 * instance reports it per asset), HTTP basic {@code username}/{@code password}, and a {@code cursor} to resume an
 * interrupted walk from. A provider reads what it needs and ignores the rest.
 */
public final class ImportRequest {

    private final URI url;
    private final String repository;
    private final String format;
    private final String username;
    private final String password;
    private final String cursor;

    public ImportRequest(URI url, String repository) {
        this(url, repository, null, null, null, null);
    }

    private ImportRequest(URI url, String repository, String format, String username, String password, String cursor) {
        this.url = url;
        this.repository = repository;
        this.format = format;
        this.username = username;
        this.password = password;
        this.cursor = cursor;
    }

    public ImportRequest withFormat(String format) {
        return new ImportRequest(url, repository, format, username, password, cursor);
    }

    public ImportRequest withCredentials(String username, String password) {
        return new ImportRequest(url, repository, format, username, password, cursor);
    }

    public ImportRequest withCursor(String cursor) {
        return new ImportRequest(url, repository, format, username, password, cursor);
    }

    public URI url() {
        return url;
    }

    public String repository() {
        return repository;
    }

    public String format() {
        return format;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String cursor() {
        return cursor;
    }
}
