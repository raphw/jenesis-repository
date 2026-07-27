package build.jenesis.repository.store.s3.test;

import build.jenesis.repository.store.s3.S3ArtifactStore;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every object the {@code s3} backend writes must carry server-side encryption - the store never relies on a
 * bucket/account default that an operator may not have set. {@link S3ArtifactStore#encrypt} is the single point every
 * {@code PutObject} (plain, content-addressed and conditional) is built through, so asserting over it proves the write
 * request the store would send. SSE-S3 (AES256) is the default; an operator-supplied {@code JENESIS_AWS_SSE_KMS_KEY_ID}
 * upgrades to {@code aws:kms}; and there is no input that turns encryption off. Needs no Docker, so it always runs.
 */
class S3ServerSideEncryptionTest {

    @Test
    void the_default_write_uses_sse_s3_aes256() {
        PutObjectRequest request = S3ArtifactStore.encrypt(PutObjectRequest.builder(), null).build();
        assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(request.ssekmsKeyId()).isNull();
    }

    @Test
    void a_blank_kms_key_still_encrypts_with_sse_s3() {
        PutObjectRequest request = S3ArtifactStore.encrypt(PutObjectRequest.builder(), "   ").build();
        assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(request.ssekmsKeyId()).isNull();
    }

    @Test
    void a_configured_kms_key_upgrades_to_aws_kms() {
        String keyId = "arn:aws:kms:us-east-1:111122223333:key/1234abcd-12ab-34cd-56ef-1234567890ab";
        PutObjectRequest request = S3ArtifactStore.encrypt(PutObjectRequest.builder(), keyId).build();
        assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(request.ssekmsKeyId()).isEqualTo(keyId);
    }
}
