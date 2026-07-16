package build.jenesis.repository.store.gcs;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * An {@link ArtifactStore} backed by a Google Cloud Storage bucket over GCS's S3-compatible XML API
 * on the AWS SDK v2. A blob is the object at its key; a tenant or repository is a key prefix (see
 * {@link #scope}). The streaming surface matches the {@code s3} backend: reads transfer straight from
 * the response stream, a ranged read issues a real {@code Range} GET, and an upload spills to a temp
 * file (the XML API needs the content length up front), never to the heap. Conditional writes differ:
 * GCS honours {@code If-Match} / {@code If-None-Match} only on reads, so the version token is the
 * object <em>generation</em> (the {@code x-goog-generation} response header) and {@link #writeVersioned}
 * maps to GCS's own precondition - {@code x-goog-if-generation-match: 0} for a create-if-absent and
 * {@code x-goog-if-generation-match: <generation>} for an update-if-unchanged - whose {@code 412
 * Precondition Failed} becomes a {@code false} return, so the caller re-reads and retries. Concurrent
 * {@code maven-metadata.xml} edits and lock acquisitions across many nodes therefore resolve through
 * GCS itself, with no database or lock service. Because the precondition is GCS-specific, versioned
 * writes need a real GCS endpoint; a generic S3-compatible store belongs on the {@code s3} backend.
 */
public final class GcsArtifactStore implements ArtifactStore {

    /** The GCS object-generation response header carrying the version token. */
    private static final String GENERATION = "x-goog-generation";
    /** The GCS write precondition: proceed only if the stored generation matches ({@code 0} = absent). */
    private static final String IF_GENERATION_MATCH = "x-goog-if-generation-match";

    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;

    public GcsArtifactStore(S3Client s3, String bucket) {
        this(s3, bucket, "");
    }

    private GcsArtifactStore(S3Client s3, String bucket, String keyPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new GcsArtifactStore(s3, bucket, keyPrefix + ArtifactStore.segment(tenant) + "/");
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key));
            return true;
        } catch (S3Exception e) {
            // Only a 404 means absent; a throttle or auth failure must fail the request loudly, or a published
            // artifact silently turns into a miss (served as 404) for as long as the backend misbehaves.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public long size(String key) throws IOException {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key)).contentLength();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return -1L;
            }
            throw new IOException("Could not size " + key, e);
        }
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try {
            if (out instanceof ArtifactStore.RangedSink ranged) {
                String range = "bytes=" + ranged.offset() + "-" + (ranged.offset() + ranged.length() - 1);
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                        b -> b.bucket(bucket).key(keyPrefix + key).range(range))) {
                    in.transferTo(ranged.sink());
                }
            } else {
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key))) {
                    in.transferTo(out);
                }
            }
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key));
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        // The XML API needs the content length up front, so buffer the (possibly large) body to a
        // temp file rather than into memory, then upload from the file.
        Path temporary = Files.createTempFile("gcs-artifact-", null);
        try {
            Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            s3.putObject(b -> b.bucket(bucket).key(keyPrefix + key), RequestBody.fromFile(temporary));
        } catch (S3Exception e) {
            throw new IOException("Could not write " + key, e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        // The upload needs the content length and the key up front, but a content-addressed key is the hash of
        // the very bytes being written; buffer the (possibly large) body to a temp file while digesting it, then
        // upload from the file under blobs/<hash> - never holding the whole artifact in memory.
        Path temporary = Files.createTempFile("gcs-artifact-", null);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String key = "blobs/" + HexFormat.of().formatHex(digest.digest());
            if (!exists(key)) {
                s3.putObject(b -> b.bucket(bucket).key(keyPrefix + key), RequestBody.fromFile(temporary));
            }
            return key.substring("blobs/".length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (S3Exception e) {
            throw new IOException("Could not write blob", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(keyPrefix + key));
        } catch (S3Exception e) {
            throw new IOException("Could not delete " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        String base = keyPrefix + (prefix.isEmpty() ? "" : prefix + "/");
        TreeSet<String> names = new TreeSet<>();
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(base).delimiter("/"))) {
            page.commonPrefixes().forEach(common -> {
                String name = common.prefix().substring(base.length());
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                if (!name.isEmpty()) {
                    names.add(name);
                }
            });
            for (S3Object object : page.contents()) {
                String name = object.key().substring(base.length());
                if (!name.isEmpty() && name.indexOf('/') < 0) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        if (limit <= 0) {
            return;
        }
        String base = keyPrefix + (prefix.isEmpty() ? "" : prefix + "/");
        // GCS's XML API honours the same list-objects-v2 start-after pagination as S3, so this mirrors the s3
        // backend, including the name-order repair: the stream arrives in raw key order, where a container's
        // grouped prefix at `name + "/"` sorts AFTER a sibling whose name extends this one past a character
        // below '/' (`app.txt` the object precedes `app/` the prefix, yet the child `app` pages first) - so
        // every name parks and the smallest parked one releases only once no smaller-named child can still
        // arrive (held()). A released name at or below startAfter is dropped: the server-side start-after skips
        // the boundary's own object but not a same-named container's grouped prefix, and a prefix-child of the
        // boundary was already paged by the call that emitted the boundary itself.
        TreeSet<String> pending = new TreeSet<>();
        int emitted = 0;
        String last = null;
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> {
            b.bucket(bucket).prefix(base).delimiter("/").maxKeys(Math.min(limit + 1, 1000));
            if (!startAfter.isEmpty()) {
                b.startAfter(base + startAfter);
            }
        })) {
            List<String> ordered = new ArrayList<>();
            for (S3Object object : page.contents()) {
                String relative = object.key().substring(base.length());
                if (!relative.isEmpty() && relative.indexOf('/') < 0) {
                    ordered.add(relative);
                }
            }
            for (CommonPrefix common : page.commonPrefixes()) {
                String relative = common.prefix().substring(base.length());
                if (relative.length() > 1 && relative.indexOf('/') == relative.length() - 1) {
                    ordered.add(relative);
                }
            }
            Collections.sort(ordered);
            for (String relative : ordered) {
                while (!pending.isEmpty() && !held(pending.first(), relative)) {
                    String name = pending.pollFirst();
                    if (name.compareTo(startAfter) > 0) {
                        consumer.accept(name);
                        last = name;
                        if (++emitted == limit) {
                            return;
                        }
                    }
                }
                String name = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
                if (!name.equals(last)) {
                    pending.add(name); // a leaf and a same-named container page as one child
                }
            }
        }
        for (String name : pending) {
            if (name.compareTo(startAfter) > 0) {
                consumer.accept(name);
                if (++emitted == limit) {
                    return;
                }
            }
        }
    }

    /** Whether {@code name} may not be paged out yet at stream position {@code relative}: a proper prefix of it
     *  whose next character sorts below {@code '/'} could still arrive as a grouped prefix (its container key
     *  {@code prefix + "/"} sorts at or past the position), and that shorter child name must page first. */
    private static boolean held(String name, String relative) {
        for (int index = 1; index < name.length(); index++) {
            if (name.charAt(index) < '/' && relative.compareTo(name.substring(0, index) + "/") <= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key))) {
            byte[] content = in.readAllBytes();
            String generation = in.response().sdkHttpResponse().firstMatchingHeader(GENERATION).orElseThrow(
                    () -> new IOException("The endpoint returned no " + GENERATION + " header for " + key
                            + " - versioned writes need a real GCS XML endpoint; use the s3 backend for"
                            + " generic S3-compatible stores"));
            return Optional.of(new Versioned(content, generation));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        String generation = expected == null ? "0" : (String) expected;
        try {
            s3.putObject(b -> b.bucket(bucket).key(keyPrefix + key)
                            .overrideConfiguration(c -> c.putHeader(IF_GENERATION_MATCH, generation)),
                    RequestBody.fromBytes(content));
            return true;
        } catch (S3Exception e) {
            int status = e.statusCode();
            if (status == 412 || status == 409 || status == 404) {
                return false;
            }
            throw new IOException("Could not write " + key, e);
        }
    }
}
