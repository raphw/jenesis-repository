package build.jenesis.repository;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Decouples the artifact bytes from their publication. Each uploaded blob is stored once, content-addressed
 * by its SHA-256 ({@code blobs/<hash>}), so identical bytes published under several coordinates dedupe to one
 * object and live independently of any path. A publication is a small pointer ({@code publish/<request-path>
 * -> <hash>}); the Maven view and the Jenesis module view are both pointers to the same blob, which is how
 * one upload serves both layouts and a republish is just a pointer update.
 *
 * Publishing goes either way. A module published under {@code /module/...} is also given its Maven view; and
 * a Maven jar published under {@code /maven/...} is inspected for a {@code module-info} or an
 * {@code Automatic-Module-Name} and, if it has one, also given its module view (and the bridge binding).
 */
public final class Publication {

    private final ArtifactStore store;
    private final ModuleBridge bridge;

    public Publication(ArtifactStore store) {
        this.store = store;
        this.bridge = new ModuleBridge(store);
    }

    /** Resolve a request path ({@code /maven/...}, {@code /module/...}, {@code /artifact/...}) to the blob bytes; false if unpublished. */
    public boolean serve(String requestPath, OutputStream out) throws IOException {
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned("publish" + requestPath);
        if (pointer.isEmpty()) {
            return false;
        }
        String hash = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
        if (!store.exists("blobs/" + hash)) {
            return false;
        }
        store.read("blobs/" + hash, out);
        return true;
    }

    /** Store the blob once (content-addressed, deduped), then publish it under both views. */
    public void publish(String requestPath, byte[] content) throws IOException {
        String hash = storeBlob(content);
        link(requestPath, hash);
        if (requestPath.startsWith("/maven/")) {
            crossPublishModule(requestPath, content, hash);
        } else {
            crossPublishMaven(requestPath, hash);
        }
    }

    /** A Maven jar that carries a module name also gets its module view and the bridge binding. */
    private void crossPublishModule(String requestPath, byte[] content, String hash) throws IOException {
        String[] coordinate = mavenCoordinate(requestPath);
        if (!requestPath.endsWith(".jar") || coordinate == null) {
            return;
        }
        String module = moduleName(content);
        if (module == null) {
            return;
        }
        String version = coordinate[2];
        link("/module/" + module + "/" + version + "/" + module + ".jar", hash);
        link("/module/" + module + "/" + module + ".jar", hash);
        bridge.register(module, coordinate[0] + ":" + coordinate[1]);
    }

    /** A module also gets its Maven view. The skeleton synthesizes the coordinate; production derives it from a
     *  supplied pom or the sormuras mapping and generates the POM with {@link PomGenerator}. */
    private void crossPublishMaven(String requestPath, String hash) throws IOException {
        String[] reference = moduleReference(requestPath);
        if (reference == null) {
            return;
        }
        String module = reference[0], version = reference[1];
        int dot = module.lastIndexOf('.');
        String groupId = dot < 0 ? module : module.substring(0, dot);
        String artifactId = dot < 0 ? module : module.substring(dot + 1);
        link("/maven/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version
                + "/" + artifactId + "-" + version + ".jar", hash);
        bridge.register(module, groupId + ":" + artifactId);
    }

    /** Store content once, content-addressed, and return its hash - the primitive a staging deploy uses to hold
     *  bytes before any view points at them. */
    public String storeBlob(byte[] content) throws IOException {
        String hash = sha256(content);
        if (!store.exists("blobs/" + hash)) {
            store.write("blobs/" + hash, new ByteArrayInputStream(content));
        }
        return hash;
    }

    /** Point a request path at an already-stored blob - the primitive promotion uses to publish a reviewed blob
     *  into the release layout without re-uploading it. */
    public void link(String requestPath, String hash) throws IOException {
        Object token = store.readVersioned("publish" + requestPath).map(ArtifactStore.Versioned::token).orElse(null);
        store.writeVersioned("publish" + requestPath, hash.getBytes(StandardCharsets.UTF_8), token);
    }

    /** The content hash a path currently points at, or empty if nothing is published there. */
    public Optional<String> blob(String requestPath) throws IOException {
        return store.readVersioned("publish" + requestPath)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim());
    }

    /** Remove a single published pointer; the blob it referenced is left for a later garbage collection, since
     *  another pointer (a deduped coordinate, a latest mirror) may still reference it. */
    public void unpublish(String requestPath) throws IOException {
        if (store.readVersioned("publish" + requestPath).isPresent()) {
            store.delete("publish" + requestPath);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String[] mavenCoordinate(String requestPath) {
        String[] segments = requestPath.substring("/maven/".length()).split("/");
        if (segments.length < 4) {
            return null;
        }
        String version = segments[segments.length - 2];
        String artifactId = segments[segments.length - 3];
        String groupId = String.join(".", Arrays.copyOf(segments, segments.length - 3));
        return new String[]{groupId, artifactId, version};
    }

    private static String[] moduleReference(String requestPath) {
        String tail = requestPath.startsWith("/module/")
                ? requestPath.substring("/module/".length())
                : requestPath.substring("/artifact/".length());
        String[] segments = tail.split("/");
        return segments.length < 3 ? null : new String[]{segments[0], segments[1]};
    }

    private static String moduleName(byte[] jar) {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            String automatic = in.getManifest() == null ? null
                    : in.getManifest().getMainAttributes().getValue("Automatic-Module-Name");
            for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
                if (entry.getName().equals("module-info.class")) {
                    return ModuleDescriptor.read(in).name();
                }
            }
            return automatic;
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }
}
