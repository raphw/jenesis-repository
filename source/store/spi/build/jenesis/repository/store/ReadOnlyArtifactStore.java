package build.jenesis.repository.store;

import module java.base;

/**
 * An {@link ArtifactStore} decorator that refuses every write, so a deployment configured read-only
 * ({@code jenesis.repository.read-only=true}) serves reads normally while every mutation - a hosted publish, a
 * {@code maven-metadata.xml} compare-and-set, a delete, a content-addressed blob write - is rejected at this single
 * low-level choke point, whether it originates at an HTTP write endpoint or an internal path (a write-through proxy
 * cache, an import replay, a background sweep). Because every serving, routing, tenant and console bean resolves
 * through the one wrapped store, wrapping here refuses <em>every</em> write by construction, not just the ones an
 * endpoint guard remembers to cover.
 *
 * <p>The read methods pass straight through to the delegate; {@link #scope} re-wraps the scoped delegate so every
 * tenant / repository subspace stays read-only too (the same recursion {@link QuotaArtifactStore#scope} uses). A
 * refused write raises {@link ReadOnlyException} before the delegate is touched - no partial bytes are stored - which
 * a server maps to HTTP {@code 403}. This wrapper is applied only when the deployment opts in, so an ordinary
 * read-write deployment never pays for it.
 */
public final class ReadOnlyArtifactStore implements ArtifactStore {

    private final ArtifactStore delegate;

    public ReadOnlyArtifactStore(ArtifactStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new ReadOnlyArtifactStore(delegate.scope(tenant));
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        delegate.read(key, out);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return delegate.open(key);
    }

    @Override
    public long size(String key) throws IOException {
        return delegate.size(key);
    }

    @Override
    public List<String> list(String prefix) {
        return delegate.list(prefix);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        delegate.page(prefix, startAfter, limit, consumer);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(key);
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public void delete(String key) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        throw new ReadOnlyException();
    }
}
