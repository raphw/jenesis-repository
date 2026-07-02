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

    private final Path root;

    public FilesystemArtifactStore(Path root) {
        this.root = root;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new FilesystemArtifactStore(root.resolve(tenant));
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
        try (OutputStream out = Files.newOutputStream(temp)) {
            in.transferTo(out);
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
            Files.deleteIfExists(parent);
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
        return Optional.of(new Versioned(Files.readAllBytes(path), Files.getLastModifiedTime(path).toMillis()));
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        Path path = resolve(key);
        Object current = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : null;
        if (!Objects.equals(current, expected)) {
            return false;
        }
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".meta", ".tmp");
        Files.write(temp, content);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }
}
