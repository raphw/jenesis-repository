package build.jenesis.repository.store.filesystem;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The default {@link ArtifactStore}: blobs under a mounted root directory, keyed by their object path.
 * Version tokens are the file's last-modified time, so {@link #writeVersioned} is a last-modified
 * compare-and-set, adequate for a single node; a clustered deployment uses an object-store backend whose
 * ETag / generation gives true cross-node compare-and-set.
 */
public final class FilesystemArtifactStore implements ArtifactStore {

    /** Striped monitors for {@link #writeVersioned}: the last-modified compare-and-set is a check-then-move, so two
     *  in-process threads holding the same token would otherwise both pass the check and both land - a lost update on
     *  the very node the mtime token is documented adequate for. Static, so every scoped view (each a new instance
     *  over the same directory tree) serializes against the same stripes; two unrelated keys sharing a stripe merely
     *  serialize a small-object write, never a blob stream. */
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int index = 0; index < LOCKS.length; index++) {
            LOCKS[index] = new Object();
        }
    }

    private final Path root;

    public FilesystemArtifactStore(Path root) {
        this.root = root;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new FilesystemArtifactStore(root.resolve(ArtifactStore.segment(tenant)));
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Path escapes the store root: " + key);
        }
        return path;
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try (InputStream in = Files.newInputStream(resolve(key))) {
            if (out instanceof ArtifactStore.RangedSink ranged) {
                in.skipNBytes(ranged.offset());
                ArtifactStore.copy(in, ranged.sink(), ranged.length());
            } else {
                in.transferTo(out);
            }
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        Path path = resolve(key);
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".upload", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        Path blobs = resolve("blobs");
        Files.createDirectories(blobs);
        Path temp = Files.createTempFile(blobs, ".upload", ".tmp");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temp)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            Path blob = blobs.resolve(hash);
            if (Files.isRegularFile(blob)) {
                Files.delete(temp);
            } else {
                Files.move(temp, blob, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return hash;
        } catch (NoSuchAlgorithmException e) {
            Files.deleteIfExists(temp);
            throw new IllegalStateException(e);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    @Override
    public long size(String key) throws IOException {
        Path path = resolve(key);
        return Files.isRegularFile(path) ? Files.size(path) : -1L;
    }

    @Override
    public void delete(String key) throws IOException {
        Path path = resolve(key);
        Files.deleteIfExists(path);
        Path parent = path.getParent(), top = root.normalize();
        while (parent != null && !parent.equals(top) && isEmpty(parent)) {
            try {
                Files.deleteIfExists(parent);
            } catch (DirectoryNotEmptyException _) {
                return; // a concurrent write repopulated the container between the check and the tidy - keep it
            }
            parent = parent.getParent();
        }
    }

    private static boolean isEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException _) {
            return false;
        }
    }

    @Override
    public List<String> list(String prefix) {
        Path dir = resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString())
                    // Skip an atomic write's in-flight .upload*.tmp file, a sibling here until it is renamed
                    // into place, so a concurrent listing never returns it as if it were a stored entry.
                    .filter(name -> !(name.startsWith(".upload") && name.endsWith(".tmp")))
                    .sorted().toList();
        } catch (IOException _) {
            return List.of();
        }
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        // Token before content: a write landing in between then pairs OLD token with NEW content, so a
        // compare-and-set from this read loses and retries - the safe direction. The reverse order would pair
        // a fresh token with stale content and let a stale update pass as current.
        long token = Files.getLastModifiedTime(path).toMillis();
        return Optional.of(new Versioned(Files.readAllBytes(path), token));
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        Path path = resolve(key);
        synchronized (LOCKS[Math.floorMod(path.hashCode(), LOCKS.length)]) {
            Object current = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : null;
            if (!Objects.equals(current, expected)) {
                return false;
            }
            Files.createDirectories(path.getParent());
            // The same .upload*.tmp shape a keyed write spools through, so list()'s in-flight filter hides this
            // temp file too and an aborted write never leaves it behind.
            Path temp = Files.createTempFile(path.getParent(), ".upload", ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                // The token must advance on every successful update: two writes inside one clock tick would
                // otherwise leave it unchanged, and a third writer holding the pre-update token would still pass
                // the compare - a stale write disguised as a fresh one.
                if (current != null && Files.getLastModifiedTime(path).toMillis() <= (long) current) {
                    Files.setLastModifiedTime(path, FileTime.fromMillis((long) current + 1));
                }
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            return true;
        }
    }
}
