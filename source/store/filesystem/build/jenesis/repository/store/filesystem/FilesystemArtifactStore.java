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

    /** Create an upload temp file in {@code dir}, (re-)creating the directory first and retrying if a concurrent
     *  {@link #delete} tidied the now-empty container away between the create-directory and the create-file. Without
     *  the retry a publish into a directory another thread is emptying fails with a spurious {@code NoSuchFileException}
     *  - the {@link #delete} tidy already guards the reverse direction (its {@code DirectoryNotEmptyException} catch),
     *  so this closes the other half of the same race. */
    private static Path createUploadTemp(Path dir) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.createDirectories(dir);
                return Files.createTempFile(dir, ".upload", ".tmp");
            } catch (NoSuchFileException e) {
                if (attempt >= 4) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        Path path = resolve(key);
        Path temp = createUploadTemp(path.getParent());
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
        Path temp = createUploadTemp(blobs);
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
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        Path dir = resolve(prefix);
        if (limit <= 0 || !Files.isDirectory(dir)) {
            return;
        }
        // A directory listing is unordered and a filesystem has no start-at seek, so select the page in one
        // bounded scan: keep the limit smallest names past startAfter in a capped TreeSet - O(limit) memory
        // however many millions of entries the directory holds, where sorting list() would buffer them all.
        TreeSet<String> smallest = new TreeSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                // The same in-flight .upload*.tmp filter as list(), so a concurrent atomic write never pages out.
                if (name.startsWith(".upload") && name.endsWith(".tmp") || name.compareTo(startAfter) <= 0) {
                    continue;
                }
                if (smallest.size() < limit) {
                    smallest.add(name);
                } else if (name.compareTo(smallest.last()) < 0) {
                    smallest.add(name);
                    smallest.pollLast();
                }
            }
        } catch (IOException _) {
            return; // mirror list(): a vanished or unreadable container pages as empty
        }
        smallest.forEach(consumer);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        // The isRegularFile probe and the token/content reads are not one atomic operation: a concurrent delete can
        // vanish the file in the window between the probe and the reads (or between reading the token and the bytes),
        // which throws NoSuchFileException (or, on some providers, FileNotFoundException) where the contract - and the
        // object-store backends' 404 -> empty behaviour - is Optional.empty(). Map that race to absent, so a reader
        // that lost to a delete simply sees no object, never an escaping exception.
        try {
            // Token before content: a write landing in between then pairs OLD token with NEW content, so a
            // compare-and-set from this read loses and retries - the safe direction. The reverse order would pair
            // a fresh token with stale content and let a stale update pass as current.
            long token = Files.getLastModifiedTime(path).toMillis();
            return Optional.of(new Versioned(Files.readAllBytes(path), token));
        } catch (NoSuchFileException | FileNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        Path path = resolve(key);
        synchronized (LOCKS[Math.floorMod(path.hashCode(), LOCKS.length)]) {
            Object current = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : null;
            if (!Objects.equals(current, expected)) {
                return false;
            }
            // The same .upload*.tmp shape a keyed write spools through, so list()'s in-flight filter hides this
            // temp file too and an aborted write never leaves it behind; createUploadTemp re-creates the parent if a
            // concurrent delete tidied it away.
            Path temp = createUploadTemp(path.getParent());
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
