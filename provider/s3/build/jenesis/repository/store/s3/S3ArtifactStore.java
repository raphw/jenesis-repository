package build.jenesis.repository.store.s3;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

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

    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;

    public S3ArtifactStore(S3Client s3, String bucket) {
        this(s3, bucket, "");
    }

    private S3ArtifactStore(S3Client s3, String bucket, String keyPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new S3ArtifactStore(s3, bucket, keyPrefix + tenant + "/");
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key));
            return true;
        } catch (S3Exception e) {
            return false;
        }
    }

    @Override
    public long size(String key) throws IOException {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key)).contentLength();
        } catch (S3Exception e) {
            return -1L;
        }
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key))) {
            in.transferTo(out);
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        // S3 PutObject needs the content length up front, so buffer the (possibly large) body to a
        // temp file rather than into memory, then upload from the file.
        Path temporary = Files.createTempFile("s3-artifact-", null);
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
        // S3 PutObject needs the content length and the key up front, but a content-addressed key is the hash of
        // the very bytes being written; buffer the (possibly large) body to a temp file while digesting it, then
        // upload from the file under blobs/<hash> - never holding the whole artifact in memory.
        Path temporary = Files.createTempFile("s3-artifact-", null);
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
                s3.putObject(b -> b.bucket(bucket).key(keyPrefix + key).ifNoneMatch("*"), RequestBody.fromBytes(content));
            } else {
                s3.putObject(b -> b.bucket(bucket).key(keyPrefix + key).ifMatch((String) expected), RequestBody.fromBytes(content));
            }
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
