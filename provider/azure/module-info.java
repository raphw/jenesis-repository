/**
 * The Azure Blob artifact-store backend. azure-storage-blob ships a real Java module, so this is a plain
 * requires; the rest of the Azure SDK closure resolves transitively through Maven and is pinned here. A
 * pure storage provider: it implements the {@code ArtifactStore} SPI and is discovered through
 * {@code provides}, so the server adds it to its module graph at deploy time and selects it with
 * {@code jenesis.repository.store=azure-blob}. The version token is the blob ETag, giving a true
 * cross-node compare-and-set on conditional writes (see {@code AzureArtifactStore}).
 *
 * @jenesis.release 25
 * @jenesis.pin com.azure.storage.blob 12.35.0
 * @jenesis.pin com.azure/azure-core 1.58.1 SHA-256/7b339126e92af79b07fcf96fe16fa5ba2a2854bb8ce7e03ac4776b9474fe7df5
 * @jenesis.pin com.azure/azure-core-http-netty 1.16.5 SHA-256/61091ba5634e711e396721edfcca5c6782be1c1e86f2ecf856eb57aa20260c0c
 * @jenesis.pin com.azure/azure-json 1.5.1 SHA-256/bad21d5eb306d82b85951b58a1d9e501a9b09970e452bee6d4d445fd5a91c519
 * @jenesis.pin com.azure/azure-storage-blob 12.35.0 SHA-256/c1f7dac599b0c057e406db76e7684bf2a5aae8f960f58bcecc18233298092eb8
 * @jenesis.pin com.azure/azure-storage-common 12.34.0 SHA-256/9ddbf4a4e7680e6d062995928b3933e496353d1e62449f2ce5662f9db0820325
 * @jenesis.pin com.azure/azure-storage-internal-avro 12.20.0 SHA-256/b80addb78cdc7ea6af99b8e76ac91c9a553e1a088850391bf2d7b3f7e2bc8dab
 * @jenesis.pin com.azure/azure-xml 1.2.1 SHA-256/08b458481b656554605215ab0b165f68e6025359e52bea4736d032328d40ba3b
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.18.7 SHA-256/4c992ecef3569e73f19cd6b3be027108fb73139bb67d55d1218ac72e92219ebc
 * @jenesis.pin com.fasterxml.jackson.core/jackson-core 2.18.7 SHA-256/e1c578d374f519aa9aa74cbdc251c6705ffa08ac78faea5fa36bad213de30dc8
 * @jenesis.pin com.fasterxml.jackson.core/jackson-databind 2.18.7 SHA-256/aa3c034534fce966b6dbd706b1f466b8a15c266127e5a15f96522091093dbd9b
 * @jenesis.pin com.fasterxml.jackson.datatype/jackson-datatype-jsr310 2.18.7 SHA-256/29b8f1f8e055653297b07c3844a056541bdbf5c8199517598d9fa6edbefcc82e
 * @jenesis.pin com.google.code.findbugs/jsr305 3.0.2 SHA-256/766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7
 * @jenesis.pin com.google.code.gson/gson 2.8.9 SHA-256/d3999291855de495c94c743761b8ab5176cfeabe281a5ab0d8e8d45326fd703e
 * @jenesis.pin commons-io/commons-io 2.20.0 SHA-256/df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72
 * @jenesis.pin io.netty/netty-buffer 4.1.135.Final SHA-256/2a194f99fc93d07c4d442d04ac71bd2dc56d3188cd0e4270cdc2a953d1956bf9
 * @jenesis.pin io.netty/netty-codec 4.1.135.Final SHA-256/7252171264dbb5bb8ed38e77f89643b31e3cabc96144ec27b6882435d718a61e
 * @jenesis.pin io.netty/netty-codec-dns 4.1.135.Final SHA-256/5e996d7ac7597f368ab114fbb91d16788918c7e5bf166345c51e56db54d50fd1
 * @jenesis.pin io.netty/netty-codec-http 4.1.135.Final SHA-256/4018529d3d6aecf4044b98c75d9a90c91839ddf49c7aa484c5ac81c90a15da02
 * @jenesis.pin io.netty/netty-codec-http2 4.1.135.Final SHA-256/aa4e81ab5fa3b7b243eb3e814aa582ab26c073d31b0abffdbb58ee150fa49c16
 * @jenesis.pin io.netty/netty-codec-socks 4.1.135.Final SHA-256/ec7a39e8d7d7e223014115a021273f011c3cb1e8fb187cbfb90a74e76d68c25c
 * @jenesis.pin io.netty/netty-common 4.1.135.Final SHA-256/26775ca95820711403cf065fa2ec0134a0a04ff5417c688c0237aee68b55838d
 * @jenesis.pin io.netty/netty-handler 4.1.135.Final SHA-256/245e74e04b6f4e8ef98853152412e3bf1499ce6fcf15329b798c8ce36c3537e2
 * @jenesis.pin io.netty/netty-handler-proxy 4.1.135.Final SHA-256/75661010630a44468f0e85d7ed8be7779c0cb1369fe85d30799cedc52e9ed3b7
 * @jenesis.pin io.netty/netty-resolver 4.1.135.Final SHA-256/77dd03865965b6c12b9e521bddec82f035caeb33156e09c158289c5094318481
 * @jenesis.pin io.netty/netty-resolver-dns 4.1.135.Final SHA-256/ca25581e4cebd55797ef3b4d0953b75df32c1af77fe771b96bfaa9e701cdb7c3
 * @jenesis.pin io.netty/netty-resolver-dns-classes-macos 4.1.135.Final SHA-256/4aab49a507dbbe446ad2c6a7587fe69c511defa6c273ce1a559e3458a3378a5b
 * @jenesis.pin io.netty/netty-tcnative-boringssl-static 2.0.78.Final SHA-256/0e21ede32de7363affc2ae1bc412ed612853957c7081d87ca5320281db3f30bf
 * @jenesis.pin io.netty/netty-tcnative-classes 2.0.78.Final SHA-256/3ca66d8c6c0f003242f954cc1822a32445109ac25b8582840ba3d8e3c92f0a3e
 * @jenesis.pin io.netty/netty-transport 4.1.135.Final SHA-256/6bde734d1ec073142eed31b1e68cd5d68fbf241e060b37f07a164e5ecb15631c
 * @jenesis.pin io.netty/netty-transport-classes-epoll 4.1.135.Final SHA-256/9d9537ab9e15164c9f0dc0748884c148814a18d78ac6dfa65cf4b3d06068ce01
 * @jenesis.pin io.netty/netty-transport-classes-kqueue 4.1.135.Final SHA-256/b1f2c39d9bf7af4ecd1eb40b6bb92c5741460623aabf351de166beecbd06827d
 * @jenesis.pin io.netty/netty-transport-native-unix-common 4.1.135.Final SHA-256/a7895075f112611d1640a596c2678a28aab92d5681c1c14755b109b8998f995e
 * @jenesis.pin io.projectreactor.netty/reactor-netty-core 1.2.18 SHA-256/2d1ff55147102d4284c6f9c59c06d4288e3a59b1921da01647fef24869cfefc3
 * @jenesis.pin io.projectreactor.netty/reactor-netty-http 1.2.18 SHA-256/5b8409741ebe7fd95ae44519a90115352fb4bf9d32f2af579c89da7003b0db10
 * @jenesis.pin io.projectreactor/reactor-core 3.7.18 SHA-256/7d9b507c0d651de30a20dac634e7cb7ca908a7c23d57ce05e71bbb9bb79bf0c4
 * @jenesis.pin main/maven/io.netty/netty-resolver-dns-native-macos/jar/osx-x86_64 4.1.135.Final SHA-256/0c86fa27317c4172fff03a0c20286e2c62ef9d60ad78f389a83ede48a5bb54cd
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-aarch_64 2.0.78.Final SHA-256/85f6e25942df7308c9a6e66015a5ba87589d6f239231fb5b175138afe451b592
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-x86_64 2.0.78.Final SHA-256/bb830d661dc70fac2df8d147ffb64d61566211455272bb75d09d1662ec843aae
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-aarch_64 2.0.78.Final SHA-256/29019bf2e3045acaf4fd17b9e4033536141c8971939cd78cc82a12fe74fe24c1
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-x86_64 2.0.78.Final SHA-256/6c6c574bf9ee85b53f176d7de1101d348cf4374014df2ea26b691e7f335d69ba
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/windows-x86_64 2.0.78.Final SHA-256/c720390d4733fa4997f4648327fcb63a688a72afd3ddd05d368759c6c65aef6b
 * @jenesis.pin main/maven/io.netty/netty-transport-native-epoll/jar/linux-x86_64 4.1.135.Final SHA-256/18a40063da3364cffff81c6c2097fb6ebcb45c62264dabcce45aade4fdac3125
 * @jenesis.pin main/maven/io.netty/netty-transport-native-kqueue/jar/osx-x86_64 4.1.135.Final SHA-256/412e10daef5aa4647984397fa6728acf88dffd0d4c53ad91f486ea6492f8f08f
 * @jenesis.pin net.bytebuddy/byte-buddy 1.12.19 SHA-256/030704139e46f32c38d27060edee9e0676b0a0fff8a8be53461515154ba8a7be
 * @jenesis.pin net.bytebuddy/byte-buddy-agent 1.12.19 SHA-256/3a70240de7cdcde04e7c504c2327d7035b9c25ae0206881e3bf4e6798a273ed8
 * @jenesis.pin org.javassist/javassist 3.28.0-GA SHA-256/57d0a9e9286f82f4eaa851125186997f811befce0e2060ff0a15a77f5a9dd9a7
 * @jenesis.pin org.mockito/mockito-core 4.11.0 SHA-256/4b909690cab288c761eb94c0bf0e814496cf3921d8affac84cd87774530351e5
 * @jenesis.pin org.objenesis/objenesis 3.3 SHA-256/02dfd0b0439a5591e35b708ed2f5474eb0948f53abf74637e959b8e4ef69bfeb
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.reflections/reflections 0.10.2 SHA-256/938a2d08fe54050d7610b944d8ddc3a09355710d9e6be0aac838dbc04e9a2825
 * @jenesis.pin org.slf4j/slf4j-api 1.7.36 SHA-256/d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0
 */
module build.jenesis.repository.azure {
    exports build.jenesis.repository.azure to build.jenesis.repository.azure.test;
    requires build.jenesis.repository.store;
    requires com.azure.storage.blob;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.azure.AzureArtifactStoreProvider;
}
