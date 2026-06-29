/**
 * The S3-compatible artifact-store backend (AWS S3, GCS via the XML API, MinIO, LocalStack). A pure
 * storage provider: it implements the {@code ArtifactStore} SPI and is discovered through {@code provides},
 * so the server adds it to its module graph at deploy time and selects it with
 * {@code jenesis.repository.store=s3}, with no compile-time dependency from the server. The version token
 * is the object ETag, giving a true cross-node compare-and-set on conditional writes (see
 * {@code S3ArtifactStore}). The rest of the AWS SDK closure resolves transitively through Maven (no
 * exclusions are possible under the module resolver, so the full closure is pulled and pinned here).
 *
 * @jenesis.release 25
 * @jenesis.pin com.google.code.findbugs/jsr305 3.0.2 SHA-256/766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7
 * @jenesis.pin com.google.code.gson/gson 2.8.9 SHA-256/d3999291855de495c94c743761b8ab5176cfeabe281a5ab0d8e8d45326fd703e
 * @jenesis.pin commons-io/commons-io 2.20.0 SHA-256/df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72
 * @jenesis.pin io.netty/netty-buffer 4.1.135.Final SHA-256/2a194f99fc93d07c4d442d04ac71bd2dc56d3188cd0e4270cdc2a953d1956bf9
 * @jenesis.pin io.netty/netty-codec 4.1.135.Final SHA-256/7252171264dbb5bb8ed38e77f89643b31e3cabc96144ec27b6882435d718a61e
 * @jenesis.pin io.netty/netty-codec-http 4.1.135.Final SHA-256/4018529d3d6aecf4044b98c75d9a90c91839ddf49c7aa484c5ac81c90a15da02
 * @jenesis.pin io.netty/netty-codec-http2 4.1.135.Final SHA-256/aa4e81ab5fa3b7b243eb3e814aa582ab26c073d31b0abffdbb58ee150fa49c16
 * @jenesis.pin io.netty/netty-common 4.1.135.Final SHA-256/26775ca95820711403cf065fa2ec0134a0a04ff5417c688c0237aee68b55838d
 * @jenesis.pin io.netty/netty-handler 4.1.135.Final SHA-256/245e74e04b6f4e8ef98853152412e3bf1499ce6fcf15329b798c8ce36c3537e2
 * @jenesis.pin io.netty/netty-resolver 4.1.135.Final SHA-256/77dd03865965b6c12b9e521bddec82f035caeb33156e09c158289c5094318481
 * @jenesis.pin io.netty/netty-transport 4.1.135.Final SHA-256/6bde734d1ec073142eed31b1e68cd5d68fbf241e060b37f07a164e5ecb15631c
 * @jenesis.pin io.netty/netty-transport-classes-epoll 4.1.135.Final SHA-256/9d9537ab9e15164c9f0dc0748884c148814a18d78ac6dfa65cf4b3d06068ce01
 * @jenesis.pin io.netty/netty-transport-native-unix-common 4.1.135.Final SHA-256/a7895075f112611d1640a596c2678a28aab92d5681c1c14755b109b8998f995e
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.1 SHA-256/1e3d8444c3c27772e4b9d42a790f06b3345a8ece4fd16d00981f2f2460e1e772
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.2 SHA-256/7c34a25506e7207b6748cef9e91163ed03081bee805cef930d82e1d8761d62f1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4 SHA-256/2e0f4ace15db2d1609c2b06eca6012e7582afe4a99ad8d15073f62dd8edb3460
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.slf4j/slf4j-api 1.7.36 SHA-256/d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0
 * @jenesis.pin software.amazon.awssdk.auth 2.46.17
 * @jenesis.pin software.amazon.awssdk.core 2.46.17
 * @jenesis.pin software.amazon.awssdk.http.urlconnection 2.46.17
 * @jenesis.pin software.amazon.awssdk.regions 2.46.17
 * @jenesis.pin software.amazon.awssdk.services.s3 2.46.17
 * @jenesis.pin software.amazon.awssdk/annotations 2.46.17 SHA-256/98f9f6b41781620d4b625cf84bc180860d5824a294012e7074ff77f49e129392
 * @jenesis.pin software.amazon.awssdk/apache5-client 2.46.17 SHA-256/5dbcf96d87c75bfa4e4bb4243aa2f3ac041b7696ddfd3af5ef159375768c587c
 * @jenesis.pin software.amazon.awssdk/arns 2.46.17 SHA-256/f7ddb5641f77b8009437dd6278334bf81d992db831bd7a4db3b73d66f3a610c5
 * @jenesis.pin software.amazon.awssdk/auth 2.46.17 SHA-256/ea469f078a7fb945f05fc0e18b2f1f9e9f6d7fa97f822a3a413c53c6e08dc89c
 * @jenesis.pin software.amazon.awssdk/aws-core 2.46.17 SHA-256/3281031ab23504626ddbb76a2192f28da22091472e9ee4cddfad72f7f3535467
 * @jenesis.pin software.amazon.awssdk/aws-query-protocol 2.46.17 SHA-256/4586f9bfeee34ba08ea37e5c6ef67064b037d3de401f1f8121b0190769251c89
 * @jenesis.pin software.amazon.awssdk/aws-xml-protocol 2.46.17 SHA-256/8cbfda0698a4df4be9802637da2b5a68b8d325645ad57b83cb2e71ec1299b63c
 * @jenesis.pin software.amazon.awssdk/checksums 2.46.17 SHA-256/785a062e218d18846f5ce4ba3268924a7f29ed9af269873bcea8611fe31fca45
 * @jenesis.pin software.amazon.awssdk/checksums-spi 2.46.17 SHA-256/7c9e338beb0d5495c49c70c4f32e097423082405a24401a393a68c09057dd59c
 * @jenesis.pin software.amazon.awssdk/crt-core 2.46.17 SHA-256/f1f16f156a42f4920a029148489ecf0f7317a80a2b4cca010264993f8af09afd
 * @jenesis.pin software.amazon.awssdk/endpoints-spi 2.46.17 SHA-256/aa4e9cab7d29d9289bc00e18a9eef2ae7939cb1d4cbc8ee63890a360e6111437
 * @jenesis.pin software.amazon.awssdk/http-auth 2.46.17 SHA-256/5d52a9bfbb491c4f505123461c80c1acfe1e0bae1acdc494b9667089f52da607
 * @jenesis.pin software.amazon.awssdk/http-auth-aws 2.46.17 SHA-256/d588b14c191129e97cd7f6c8d53c22b469ae4ddf3d6cd407ef8c6442e605d282
 * @jenesis.pin software.amazon.awssdk/http-auth-aws-eventstream 2.46.17 SHA-256/d89ced4eb8e32a26ca931ac4247472a01d00c60f431326b601100842a1914096
 * @jenesis.pin software.amazon.awssdk/http-auth-spi 2.46.17 SHA-256/2fe8cc03ae5180a854afa4f60f0c1b39b4e51d753ef358ab85109f184f0b9fca
 * @jenesis.pin software.amazon.awssdk/http-client-spi 2.46.17 SHA-256/ba0b3d37b30c977b75f4e959297e98dae31912a14539d30e74b9d9ec02a95182
 * @jenesis.pin software.amazon.awssdk/identity-spi 2.46.17 SHA-256/6fc4ebdc03089d97d5d7eb32baf9a8a77ba5db012ce14ccc9cd372ab494c7326
 * @jenesis.pin software.amazon.awssdk/json-utils 2.46.17 SHA-256/72ec5509482efdc8ece656ce166b4d4dc349f9253e2f3fcea3f6ccdfd5c94913
 * @jenesis.pin software.amazon.awssdk/metrics-spi 2.46.17 SHA-256/66ba37e5e06180fa0f2118f3b1e3780ac46e901a2e9055ff087437fda04a0702
 * @jenesis.pin software.amazon.awssdk/netty-nio-client 2.46.17 SHA-256/92e1df3f7314869aef2b19eaa1c385b5050689c42b6b241c792a0de3c3b0197b
 * @jenesis.pin software.amazon.awssdk/profiles 2.46.17 SHA-256/c537e290eeccb21e7f15ea8095d4e1ef8ed2f45afa4b467cca432ede02ce5541
 * @jenesis.pin software.amazon.awssdk/protocol-core 2.46.17 SHA-256/b4d047127f67f25417204d8fd3d460302a0b2bc76ca12477a2f80512bc5327c9
 * @jenesis.pin software.amazon.awssdk/regions 2.46.17 SHA-256/22625109ed8d9f703b97a34fd7bb43ea785de2c8605c07b129ab802c8775bed1
 * @jenesis.pin software.amazon.awssdk/retries 2.46.17 SHA-256/d139a0b137055782e0e273249592c04ef46e4e365ebb0b9f6121c634cc080af8
 * @jenesis.pin software.amazon.awssdk/retries-spi 2.46.17 SHA-256/526014a15604513d0e28a201de4252dff52e2d12fcdc1c124e7cd941ab8e6998
 * @jenesis.pin software.amazon.awssdk/s3 2.46.17 SHA-256/e98f0e11c9efa321bc1bca4d05d018ec354cf3f14a735016819ca23fe9e59322
 * @jenesis.pin software.amazon.awssdk/sdk-core 2.46.17 SHA-256/129fa9e17b2847913f7e95e3a47de751f84348259473c360e17f6184f7107e85
 * @jenesis.pin software.amazon.awssdk/third-party-jackson-core 2.46.17 SHA-256/702689c84d4124db958e658112a84cface9933f7fd20ac5ada2497d1c54ae7bb
 * @jenesis.pin software.amazon.awssdk/url-connection-client 2.46.17 SHA-256/9a58a158c45ab62012c4e75f496dee202036532f0cbd8655dfd77dc136cc9be3
 * @jenesis.pin software.amazon.awssdk/utils 2.46.17 SHA-256/4f9ee28ee6b6d9771fad18bac10cb806d7bebc0b0abfb6515fc7b4952fbb8507
 * @jenesis.pin software.amazon.awssdk/utils-lite 2.46.17 SHA-256/1d5bcc1929c7adb9d82d3f66e95b410602bd567c7704f8c73aca4e62c35ab5dd
 * @jenesis.pin software.amazon.eventstream/eventstream 1.0.1 SHA-256/0c37d8e696117f02c302191b8110b0d0eb20fa412fce34c3a269ec73c16ce822
 */
module build.jenesis.repository.s3 {
    exports build.jenesis.repository.s3 to build.jenesis.repository.s3.test;
    requires build.jenesis.repository.store;
    requires software.amazon.awssdk.services.s3;
    requires software.amazon.awssdk.core;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.auth;
    requires software.amazon.awssdk.http.urlconnection;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.s3.S3ArtifactStoreProvider;
}
