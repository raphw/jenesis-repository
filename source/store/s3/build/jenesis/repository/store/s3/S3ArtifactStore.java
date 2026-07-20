package build.jenesis.repository.store.s3;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * An {@link ArtifactStore} backed by an S3-compatible bucket (AWS S3, GCS via the XML API, MinIO,
 * LocalStack) on the AWS SDK v2. A blob is the object at its key; a tenant or repository is a key
 * prefix (see {@link #scope}). The version token is the object ETag, so {@link #writeVersioned} is a
 * true cross-node compare-and-set: {@code expected == null} maps to a conditional {@code If-None-Match: *}
 * put (write only if the key is still absent) and a non-null token to an {@code If-Match: <etag>} put
 * (write only if the stored object is unchanged); a {@code 412 Precondition Failed} (or the {@code 409}
 * a concurrent conditional write can raise) becomes a {@code false} return, so the caller re-reads and
 * retries. Concurrent {@code maven-metadata.xml} edits and lock acquisitions across many nodes therefore
 * resolve through S3 itself, with no database or lock service.
 */
public final class S3ArtifactStore implements ArtifactStore {

    /** Owner-only (0600) permissions for the upload spool file - see {@link #spool()}. */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;
    /** The KMS key id for {@code aws:kms} encryption, or {@code null} for the SSE-S3 (AES256) default. */
    private final String kmsKeyId;

    public S3ArtifactStore(S3Client s3, String bucket) {
        this(s3, bucket, null);
    }

    public S3ArtifactStore(S3Client s3, String bucket, String kmsKeyId) {
        this(s3, bucket, "", kmsKeyId);
    }

    private S3ArtifactStore(S3Client s3, String bucket, String keyPrefix, String kmsKeyId) {
        this.s3 = s3;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new S3ArtifactStore(s3, bucket, keyPrefix + ArtifactStore.segment(tenant) + "/", kmsKeyId);
    }

    /**
     * Applies the store's server-side encryption to an object write. Every {@code PutObject} the store issues -
     * plain, content-addressed or conditional - is built through here, so an object is never written unencrypted:
     * SSE-S3 ({@link ServerSideEncryption#AES256}) by default, or {@code aws:kms} with {@code kmsKeyId} when one is
     * configured ({@code JENESIS_AWS_SSE_KMS_KEY_ID}). There is deliberately no way to switch encryption off - a
     * blank or absent key simply falls back to the AES256 default rather than disabling it.
     */
    public static PutObjectRequest.Builder encrypt(PutObjectRequest.Builder builder, String kmsKeyId) {
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            return builder.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
        }
        return builder.serverSideEncryption(ServerSideEncryption.AES256);
    }

    /**
     * A temp file for spooling an artifact body, readable and writable only by its owner. A blob is buffered here to
     * learn its length (and, for a content-addressed write, its SHA-256) before the length-prefixed S3 {@code PutObject}
     * - the body cannot stream straight through the sync client without its length up front. A shared {@code /tmp}
     * spool would leave the plaintext artifact bytes world-readable for the life of the upload, so on a POSIX
     * filesystem the file is created {@code 0600} at open time (never briefly world-readable). A non-POSIX filesystem
     * that cannot express owner-only permissions at create time falls back to a default temp file, then tightens it
     * best-effort through the {@link File} API.
     */
    private static Path spool() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile("s3-artifact-", null, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        Path temporary = Files.createTempFile("s3-artifact-", null);
        File file = temporary.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        return temporary;
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
        // S3 PutObject needs the content length up front, so buffer the (possibly large) body to an owner-only
        // temp file rather than into memory, then upload from the file.
        Path temporary = spool();
        try {
            Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key), kmsKeyId), RequestBody.fromFile(temporary));
        } catch (S3Exception e) {
            throw new IOException("Could not write " + key, e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        // S3 PutObject needs the content length and the key up front, but a content-addressed key is the hash of
        // the very bytes being written; buffer the (possibly large) body to a temp file while digesting it, then
        // upload from the file under blobs/<hash> - never holding the whole artifact in memory.
        Path temporary = spool();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String key = "blobs/" + HexFormat.of().formatHex(digest.digest());
            if (!exists(key)) {
                s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key), kmsKeyId), RequestBody.fromFile(temporary));
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
        // The stream arrives in raw key order, where a container shows up as a grouped prefix at `name + "/"` -
        // AFTER any sibling whose name extends this one past a character below '/' (the object `app.txt`
        // precedes the grouped prefix `app/`, yet the child `app` must page before `app.txt`). Emitting in
        // child-NAME order therefore parks every name and releases the smallest parked one only once no
        // smaller-named child can still arrive - see held(). A released name at or below startAfter is dropped:
        // the server-side start-after skips the boundary's own object but not a same-named container's grouped
        // prefix, and a prefix-child of the boundary (`app` for `app.txt`) re-arrives here yet was already paged
        // by the call that emitted the boundary itself.
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
            return Optional.of(new Versioned(content, in.response().eTag()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        try {
            if (expected == null) {
                s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key).ifNoneMatch("*"), kmsKeyId), RequestBody.fromBytes(content));
            } else {
                s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key).ifMatch((String) expected), kmsKeyId), RequestBody.fromBytes(content));
            }
            return true;
        } catch (S3Exception e) {
            // A bucket-level 404 (NoSuchBucket) is a misconfiguration or outage, not a CAS conflict: mapping it to a
            // false return would turn a missing/renamed bucket into silent retry-exhaustion at the caller. Surface it
            // as a real IOException. Only a key-level 404 (the object an If-Match refers to has been deleted) is the
            // benign conflict a re-read-and-retry resolves, alongside the 412/409 precondition rejections.
            if (e.awsErrorDetails() != null && "NoSuchBucket".equals(e.awsErrorDetails().errorCode())) {
                throw new IOException("Could not write " + key + ": bucket " + bucket + " does not exist", e);
            }
            int status = e.statusCode();
            if (status == 412 || status == 409 || status == 404) {
                return false;
            }
            throw new IOException("Could not write " + key, e);
        }
    }
}
