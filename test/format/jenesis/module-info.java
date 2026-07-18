/**
 * Focused unit tests for the Jenesis module layout, driving {@link build.jenesis.repository.format.jenesis.JenesisFormat}
 * through an in-memory {@code FakeExchange} and calling {@link build.jenesis.repository.format.jenesis.ModuleViewPublisher}
 * directly, against a real {@link build.jenesis.repository.store.filesystem.FilesystemArtifactStore} rooted at a JUnit
 * {@code @TempDir}: the {@code /module/} and {@code /artifact/} publish-and-serve round trip, and the cross-publish that
 * links a modular jar by module name and version (and by name alone) over one content-addressed blob.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.jenesis
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.3 SHA-256/d78396e3c5bce3f2865c9186647481e5589d34cacc632484715b686108d17c66
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.junit.jupiter 6.1.0
 * @jenesis.pin org.junit.jupiter.api 6.1.0
 * @jenesis.pin org.junit.jupiter/junit-jupiter 6.1.0 SHA-256/a4e420b5c6e8170323b4c5c97ae35bca0d620be9f9cfe37006820f53931f27a3
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.1.0 SHA-256/50f97eb800c2e888faa237a06f5a0ef445faed5567f994dac0c2b9d278a9ad20
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.1.0 SHA-256/ea707b9647084619a0fc911cefb25037540d58b2800f8ead1fc6a2baf58b1da5
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.1.0 SHA-256/b987eea3205185a76f3659a39e67503cb7b682d8b7be03be4b9f92b710f0eec0
 * @jenesis.pin org.junit.platform.commons 6.1.0
 * @jenesis.pin org.junit.platform.console 6.1.0
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.1.0 SHA-256/1d9046ab17ec7edafb0bc7945d2e59d7180fff4f28c734b823b51001e769f71b
 * @jenesis.pin org.junit.platform/junit-platform-console 6.1.0 SHA-256/715f2e54d39e02edd76c33e934341c0562769955e9ad7682898623e73485a729
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.1.0 SHA-256/3fb6be76c26ab0f94fe084e3fd0a39e1d25e22129929a61b29bd80a052b93ea5
 * @jenesis.pin org.junit.platform/junit-platform-launcher 6.1.0 SHA-256/0995e6ed244d66196cbda019e2f879504d0b48971edae9cc3dea46a1b31c0377
 * @jenesis.pin org.junit.platform/junit-platform-reporting 6.1.0 SHA-256/6bceb2bb75a5b32774beaa7c520201b863463cf922f2f2b0b6492a850af06a8b
 * @jenesis.pin org.opentest4j 1.3.0
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.5 SHA-256/df237b68847637747f0bfdb88fa9cdd9c72cc85550fad0c41ddb33869a5ca516
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 */
open module build.jenesis.repository.format.jenesis.test {
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
    // A test-only screen discovered on the publish chain, so a format-level test can assert JenesisFormat routes its
    // hosted PUT through the gate (withhold + 202 on QUARANTINE, nothing linked + 422 on REJECT). Inert otherwise.
    provides build.jenesis.repository.store.PublishInterceptor
            with build.jenesis.repository.format.jenesis.test.MarkerInterceptor;
}
