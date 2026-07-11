/**
 * The all-in-one carrier: one launchable module whose {@code requires} closure is every free SPI implementation -
 * all four layouts ({@code maven}, {@code jenesis}, {@code oci}, {@code raw}), all four store backends
 * ({@code filesystem}, {@code s3}, {@code gcs}, {@code azure}), all five import connectors, the upstream HTTP
 * fetcher ({@code proxy}), the OIDC token exchange ({@code oidc}), the token-bucket rate limiter, the credential
 * usage tracker and the web console ({@code ui}) - so the packaging {@code bundle} step emits a {@code bundle.zip}
 * carrying the complete free product, and the {@code Dockerfile} turns that one zip into the all-in-one image.
 * Nothing here names a plugin: the server keeps discovering everything through {@code ServiceLoader}, and the image
 * is trimmed by configuration instead of rebuilt - {@code jenesis.repository.<feature>=false} (settable as
 * {@code JENESIS_REPOSITORY_<FEATURE>=false} through relaxed binding) disables an implementation exactly as if its
 * module were absent, and {@code jenesis.repository.<spi>=<feature>} selects among exclusive implementations
 * (the store defaults to {@code filesystem}), per the {@code build.jenesis.repository.store.Features} convention.
 *
 * <p>{@link build.jenesis.repository.bundle.AllInOne} boots the repository server under the config name
 * {@code allinone} ({@code allinone.properties} in this module), because with the server and the console both on
 * the module path two root {@code application.properties} would be ambiguous;
 * {@link build.jenesis.repository.bundle.Console} boots the web console the same way ({@code allinone-console}),
 * so the one image also runs the console node via {@code MAINMODULE}/{@code MAINCLASS}.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.repository.bundle.AllInOne
 * @jenesis.pin ch.qos.logback/logback-classic 1.5.34 SHA-256/b65e05076a5c1aadb659b4fe4bc5fee31cb26cd70390292eb03e4a7a24cff10f
 * @jenesis.pin ch.qos.logback/logback-core 1.5.34 SHA-256/42eda264c0c650c2bec59e66151a88b708a8663dc1b49d788202d53e78b8caae
 * @jenesis.pin com.azure/azure-core 1.58.1 SHA-256/7b339126e92af79b07fcf96fe16fa5ba2a2854bb8ce7e03ac4776b9474fe7df5
 * @jenesis.pin com.azure/azure-core-http-netty 1.16.5 SHA-256/61091ba5634e711e396721edfcca5c6782be1c1e86f2ecf856eb57aa20260c0c
 * @jenesis.pin com.azure/azure-json 1.5.1 SHA-256/bad21d5eb306d82b85951b58a1d9e501a9b09970e452bee6d4d445fd5a91c519
 * @jenesis.pin com.azure/azure-storage-blob 12.35.0 SHA-256/c1f7dac599b0c057e406db76e7684bf2a5aae8f960f58bcecc18233298092eb8
 * @jenesis.pin com.azure/azure-storage-common 12.34.0 SHA-256/9ddbf4a4e7680e6d062995928b3933e496353d1e62449f2ce5662f9db0820325
 * @jenesis.pin com.azure/azure-storage-internal-avro 12.20.0 SHA-256/b80addb78cdc7ea6af99b8e76ac91c9a553e1a088850391bf2d7b3f7e2bc8dab
 * @jenesis.pin com.azure/azure-xml 1.2.1 SHA-256/08b458481b656554605215ab0b165f68e6025359e52bea4736d032328d40ba3b
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.fasterxml.jackson.core/jackson-core 2.18.7 SHA-256/e1c578d374f519aa9aa74cbdc251c6705ffa08ac78faea5fa36bad213de30dc8
 * @jenesis.pin com.fasterxml.jackson.core/jackson-databind 2.18.7 SHA-256/aa3c034534fce966b6dbd706b1f466b8a15c266127e5a15f96522091093dbd9b
 * @jenesis.pin com.fasterxml.jackson.datatype/jackson-datatype-jsr310 2.18.7 SHA-256/29b8f1f8e055653297b07c3844a056541bdbf5c8199517598d9fa6edbefcc82e
 * @jenesis.pin com.github.stephenc.jcip/jcip-annotations 1.0-1 SHA-256/4fccff8382aafc589962c4edb262f6aa595e34f1e11e61057d1c6a96e8fc7323
 * @jenesis.pin com.nimbusds/content-type 2.3 SHA-256/60349793e006fba96b532cb0c21e10e969fe0db8d87f91c3b9eaf82ba2998895
 * @jenesis.pin com.nimbusds/lang-tag 1.7 SHA-256/e8c1c594e2425bdbea2d860de55c69b69fc5d59454452449a0f0913c2a5b8a31
 * @jenesis.pin com.nimbusds/nimbus-jose-jwt 10.9 SHA-256/64d613d91140bad0dab8f0c41960f919ec8705a9ced9418146598b4b3ae71349
 * @jenesis.pin com.nimbusds/oauth2-oidc-sdk 11.37.2 SHA-256/b66e74746dcf516d77f20344e6fbcbcffe1b483b5cf1ad41ea81cec83cb27b3c
 * @jenesis.pin commons-logging/commons-logging 1.3.5 SHA-256/6d7a744e4027649fbb50895df9497d109f98c766a637062fe8d2eabbb3140ba4
 * @jenesis.pin io.micrometer/micrometer-commons 1.17.0 SHA-256/03919dc71e2417ec4b5c254c4ba924963c972e124190f73cdcb68ed51c6eede6
 * @jenesis.pin io.micrometer/micrometer-core 1.17.0 SHA-256/73503e701a377fafeaf33b71b9b8910a8d7884cbba88ab27971b33b3753b65aa
 * @jenesis.pin io.micrometer/micrometer-jakarta9 1.17.0 SHA-256/4ae9dbc9072fea8c36684a745e0e944b9540fd15027dfe7af0a186f8df43272c
 * @jenesis.pin io.micrometer/micrometer-observation 1.17.0 SHA-256/2fc95a327578d3b2a81c3ff40e646a4a21e46b0153ccbbf91690142bf80d9661
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
 * @jenesis.pin jakarta.annotation/jakarta.annotation-api 3.0.0 SHA-256/b01f55552284cfb149411e64eabca75e942d26d2e1786b32914250e4330afaa2
 * @jenesis.pin main/maven/io.netty/netty-resolver-dns-native-macos/jar/osx-x86_64 4.1.135.Final SHA-256/0c86fa27317c4172fff03a0c20286e2c62ef9d60ad78f389a83ede48a5bb54cd
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-aarch_64 2.0.78.Final SHA-256/85f6e25942df7308c9a6e66015a5ba87589d6f239231fb5b175138afe451b592
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/linux-x86_64 2.0.78.Final SHA-256/bb830d661dc70fac2df8d147ffb64d61566211455272bb75d09d1662ec843aae
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-aarch_64 2.0.78.Final SHA-256/29019bf2e3045acaf4fd17b9e4033536141c8971939cd78cc82a12fe74fe24c1
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/osx-x86_64 2.0.78.Final SHA-256/6c6c574bf9ee85b53f176d7de1101d348cf4374014df2ea26b691e7f335d69ba
 * @jenesis.pin main/maven/io.netty/netty-tcnative-boringssl-static/jar/windows-x86_64 2.0.78.Final SHA-256/c720390d4733fa4997f4648327fcb63a688a72afd3ddd05d368759c6c65aef6b
 * @jenesis.pin main/maven/io.netty/netty-transport-native-epoll/jar/linux-x86_64 4.1.135.Final SHA-256/18a40063da3364cffff81c6c2097fb6ebcb45c62264dabcce45aade4fdac3125
 * @jenesis.pin main/maven/io.netty/netty-transport-native-kqueue/jar/osx-x86_64 4.1.135.Final SHA-256/412e10daef5aa4647984397fa6728acf88dffd0d4c53ad91f486ea6492f8f08f
 * @jenesis.pin net.minidev/accessors-smart 2.6.0 SHA-256/222c9f547bb20a99fc486403a398352d1306fb671b38abd7ecab6401df170e61
 * @jenesis.pin net.minidev/json-smart 2.6.0 SHA-256/1ae4b561458afb540be8ec5c6dbb4f2e715a319a7ae64854998aaf924770d61b
 * @jenesis.pin ognl/ognl 3.3.4 SHA-256/47fdd450407ff09b57df02f466f9b4c7d32818962d65f9d98e445c8b4d047603
 * @jenesis.pin org.apache.httpcomponents.client5/httpclient5 5.6.1 SHA-256/1e3d8444c3c27772e4b9d42a790f06b3345a8ece4fd16d00981f2f2460e1e772
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5 5.4.2 SHA-256/7c34a25506e7207b6748cef9e91163ed03081bee805cef930d82e1d8761d62f1
 * @jenesis.pin org.apache.httpcomponents.core5/httpcore5-h2 5.4 SHA-256/2e0f4ace15db2d1609c2b06eca6012e7582afe4a99ad8d15073f62dd8edb3460
 * @jenesis.pin org.apache.logging.log4j/log4j-api 2.25.4 SHA-256/c4b642a7f047275215de117e0e3847eb2c7711d84a0aa7433e7b3c096daf341d
 * @jenesis.pin org.apache.logging.log4j/log4j-to-slf4j 2.25.4 SHA-256/d7b78fc0aaaa5e8ada388b29d718b0ab187e512965bed0b259bb4ab299f13db2
 * @jenesis.pin org.apache.tomcat.embed/tomcat-embed-core 11.0.22 SHA-256/78cd7cd7c104b6b87142c1b0bd902e1ce005b0245c3cefa8a06759148947200b
 * @jenesis.pin org.apache.tomcat.embed/tomcat-embed-el 11.0.22 SHA-256/1b34c33b858c141df36c501b4d809e68036c406bca3671a86facae297917c7de
 * @jenesis.pin org.apache.tomcat.embed/tomcat-embed-websocket 11.0.22 SHA-256/210e0c7ab194a76cc7283df0be365276091b042369dae125fb477828ba67e922
 * @jenesis.pin org.apache.tomcat/tomcat-annotations-api 11.0.22 SHA-256/fbe1a2ee12ea472b773bf3a6237d95b66a002c5c4fa6d38a54e69c019558cfd4
 * @jenesis.pin org.attoparser/attoparser 2.0.7.RELEASE SHA-256/75dd1c045492bff8e1963aabb28bfe903c2064e11e27fe2f0f0aff1ad3d84476
 * @jenesis.pin org.hdrhistogram/HdrHistogram 2.2.2 SHA-256/22d1d4316c4ec13a68b559e98c8256d69071593731da96136640f864fa14fad8
 * @jenesis.pin org.javassist/javassist 3.29.0-GA SHA-256/62d4065362e8969ce654f2b5541de1efb5b5bca6c146dbd38a595ea4df64cd31
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.ow2.asm/asm 9.7.1 SHA-256/8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
 * @jenesis.pin org.reactivestreams/reactive-streams 1.0.4 SHA-256/f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28
 * @jenesis.pin org.slf4j/jul-to-slf4j 2.0.18 SHA-256/cbb7d1aaaa9e871eb1a06594abd911bf97027152976edf1edc315be75239204e
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 * @jenesis.pin org.springframework.boot/spring-boot 4.1.0 SHA-256/b23951a3a7f867e38db4729b8594e1b72374516f386b1dd9cf4d5317d6d3f91f
 * @jenesis.pin org.springframework.boot/spring-boot-actuator 4.1.0 SHA-256/10279d87ab47a9a41cb69c56fc69494a54a3b84f25c853141ed234d4832e85b4
 * @jenesis.pin org.springframework.boot/spring-boot-actuator-autoconfigure 4.1.0 SHA-256/1124c22bc848e5ed557fe4543698056ef7090f134f2ccbcafb3d6c18a5613b13
 * @jenesis.pin org.springframework.boot/spring-boot-autoconfigure 4.1.0 SHA-256/0fcaa3050ba835ca6b3879f81cd48dc590a6262f53bbf10d2a95c70bf7c048ac
 * @jenesis.pin org.springframework.boot/spring-boot-health 4.1.0 SHA-256/cbd92b42254fe264a5d4556538049139aabbfdc367f5d06fb5b4cf97aa70fc18
 * @jenesis.pin org.springframework.boot/spring-boot-http-converter 4.1.0 SHA-256/f82c8913ea17a60630a5d26fa006cf79f28c7d920dbbef8760f5bc7053706fea
 * @jenesis.pin org.springframework.boot/spring-boot-jackson 4.1.0 SHA-256/10b6e63a40b257168854093f413eaab8b8a9afb7e989fcc7f6732b42c3f173d1
 * @jenesis.pin org.springframework.boot/spring-boot-micrometer-metrics 4.1.0 SHA-256/2030f79dbc59c9d84f1f4d6d2a423b46a8ba5cc277cbda75ac54a436b3ea96fb
 * @jenesis.pin org.springframework.boot/spring-boot-micrometer-observation 4.1.0 SHA-256/1f4a7a9755b38470157316dcec8a9f19b1b89864c5f742149b8db0c517f41853
 * @jenesis.pin org.springframework.boot/spring-boot-security 4.1.0 SHA-256/5e30a3ea1d62c5ef2af5a8bcf31237583f216f40dbb0b76ec2eda72981ea2bac
 * @jenesis.pin org.springframework.boot/spring-boot-security-oauth2-client 4.1.0 SHA-256/cdf7e36a52b80b0b139f82e05ea9db0e58c8711c207b25fb85dcc48bd891fae2
 * @jenesis.pin org.springframework.boot/spring-boot-servlet 4.1.0 SHA-256/5f694a7c6c357a87032bc92db0e7e0e03b64010f77892e5992c86b10f568a5ae
 * @jenesis.pin org.springframework.boot/spring-boot-starter 4.1.0 SHA-256/40352b3fc0834d5830f66d40100fc8afa8ac73e24a134c7779bd42b72f2d6506
 * @jenesis.pin org.springframework.boot/spring-boot-starter-actuator 4.1.0 SHA-256/f6f6c4166430953515336ecf3b25ea467e24c4e5705cbcc8cefa273a4bd6bde8
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jackson 4.1.0 SHA-256/4214c534cca5c7c7e1cf92db90f178a3dffdede503fb68ac3c0dd905f331431f
 * @jenesis.pin org.springframework.boot/spring-boot-starter-logging 4.1.0 SHA-256/73a6a42d2b6a589bd722aa107800829d0b20b731d94135f53c4b744db8beafbf
 * @jenesis.pin org.springframework.boot/spring-boot-starter-micrometer-metrics 4.1.0 SHA-256/ad4a34ba880e6a8c811e90c1c034b937b8a78030eae60ec5b43826c42590c802
 * @jenesis.pin org.springframework.boot/spring-boot-starter-oauth2-client 4.1.0 SHA-256/bb4c0a7b44c1dfe4170f1122823cb37fc3c6e3975e5573d63712cb68b8a6c2cd
 * @jenesis.pin org.springframework.boot/spring-boot-starter-security 4.1.0 SHA-256/5370ad6bd847e85675ee81a2da98f5fabcfc8649197b0a873417a051aa435c41
 * @jenesis.pin org.springframework.boot/spring-boot-starter-thymeleaf 4.1.0 SHA-256/e9392e2da88700e1c52f4d6a154f48129f1a926f2dea8d9a568dcf9c29ad3d09
 * @jenesis.pin org.springframework.boot/spring-boot-starter-tomcat 4.1.0 SHA-256/3e8cbd141ee6f4f2acfaf6320f1951307816f086de426d08eac19ef4a57d7e90
 * @jenesis.pin org.springframework.boot/spring-boot-starter-tomcat-runtime 4.1.0 SHA-256/135fa0b7a4232c64b975f348cab39e2259d36a18e14768f5f213147a4febf68d
 * @jenesis.pin org.springframework.boot/spring-boot-starter-web 4.1.0 SHA-256/d2732bdc307d3628d680d32758b300972109f499ec8e023bd663cdad002c67c6
 * @jenesis.pin org.springframework.boot/spring-boot-thymeleaf 4.1.0 SHA-256/5df118e86f83b58a8a3f8e7f37d114b72ae175aa3e2074d008c6548c20d0f751
 * @jenesis.pin org.springframework.boot/spring-boot-tomcat 4.1.0 SHA-256/011e662eb6f9f4a80c5dacf914cfca8a25a33fcd753736ec453dcf701337dd24
 * @jenesis.pin org.springframework.boot/spring-boot-web-server 4.1.0 SHA-256/a8541ccbd29f5a8db7e6092fa83463aa4d1c002fac07b8b5babe118ad6c4a3d3
 * @jenesis.pin org.springframework.boot/spring-boot-webmvc 4.1.0 SHA-256/ab21735a550cbfefaa4ad6ffbb1a891592580ef05ad729cd2025bc0245862b55
 * @jenesis.pin org.springframework.security/spring-security-config 7.1.0 SHA-256/3234035bb5ccd45a9367ce526723d6b8da501c5c3f725b54a98354f922c2e978
 * @jenesis.pin org.springframework.security/spring-security-core 7.1.0 SHA-256/f8cecce9e65db9fe9ea42ca92b04d6e4e4320ff9d492aa60b753716ea397262c
 * @jenesis.pin org.springframework.security/spring-security-crypto 7.1.0 SHA-256/6f6957548a28451712e53b94a3e77057735b2fcec04c99ca6dd555b574453a98
 * @jenesis.pin org.springframework.security/spring-security-oauth2-client 7.1.0 SHA-256/6a90451711b3623f7f705ae5d555131b18be1e8f7299d7fc423fcf2e7b87128b
 * @jenesis.pin org.springframework.security/spring-security-oauth2-core 7.1.0 SHA-256/68c6bfbace2a429cdd277ce848f8a1a6ea8e33bb386fa2ba19636821457c376f
 * @jenesis.pin org.springframework.security/spring-security-oauth2-jose 7.1.0 SHA-256/a1620a4424e40035dc33d3a53d98a9e978a96d98334a43aaef0bbd60268d0f8c
 * @jenesis.pin org.springframework.security/spring-security-web 7.1.0 SHA-256/1deee612104ce85ec815076b80578cd8e82c07067e122068f09fbfef860b3cb1
 * @jenesis.pin org.springframework/spring-aop 7.0.8 SHA-256/1178f039e087884174e2affc46e484f4a8bd7f2a4e011d33dd9137709f740f80
 * @jenesis.pin org.springframework/spring-beans 7.0.8 SHA-256/6ec2e361a8872a71d8b1ff66f1bcb8cfa29fcc437931998919da7cecfb59b45b
 * @jenesis.pin org.springframework/spring-context 7.0.8 SHA-256/1eb7d552414ebac00e30ab3e809138d810785f6d2c4271db77cdf0181f308f19
 * @jenesis.pin org.springframework/spring-core 7.0.8 SHA-256/726ba2a5130833644bdf267a55ff26e1f52e8dcc9aa1ffa06904ca9c14619f25
 * @jenesis.pin org.springframework/spring-expression 7.0.8 SHA-256/3c97c38ab59c77ee886e08ccf8096f6bb58a1245f68dfed7a40e93f41c435f9a
 * @jenesis.pin org.springframework/spring-web 7.0.8 SHA-256/4d4ed7ecb0453d25d735ea27d025ea36b003c3d29cb7d006bedd6d5188a2f5c0
 * @jenesis.pin org.springframework/spring-webmvc 7.0.8 SHA-256/48f7e1e2d0d46e98ed3fa30d5a64cb1f7ed2aa339a82edcd87289ed8ff216f04
 * @jenesis.pin org.thymeleaf/thymeleaf 3.1.5.RELEASE SHA-256/4011795f8494dd69e764b7709443dd13d3068ba8ac37624f61d7084f4429cbe2
 * @jenesis.pin org.thymeleaf/thymeleaf-spring6 3.1.5.RELEASE SHA-256/fd5d306052d7aa6769a8ec77778d328e6f7c83af5ac074df38035bbb1e9cd72b
 * @jenesis.pin org.unbescape/unbescape 1.1.6.RELEASE SHA-256/597cf87d5b1a4f385b9d1cec974b7b483abb3ee85fc5b3f8b62af8e4bec95c2c
 * @jenesis.pin org.yaml/snakeyaml 2.6 SHA-256/c8f7a98e7394adda02f6317249710e4d1b4c7a25aa8c7eace0c2eea52eb8bf85
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
 * @jenesis.pin spring.boot 4.1.0
 * @jenesis.pin spring.context 7.0.8
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.0 SHA-256/5e353ce53c6901105dfcbf183e3220c17072e334e552b818a4bb1b99decea596
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.0 SHA-256/3ef94a3dddeafc247c50230fad0315981b2ce4ae6e91cfb4368a86f328904e4f
 */
open module build.jenesis.repository.bundle {
    exports build.jenesis.repository.bundle to build.jenesis.repository.bundle.test;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.s3;
    requires build.jenesis.repository.store.gcs;
    requires build.jenesis.repository.store.azure;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.importer.artifactory;
    requires build.jenesis.repository.importer.index;
    requires build.jenesis.repository.importer.jenesis;
    requires build.jenesis.repository.importer.maven;
    requires build.jenesis.repository.importer.nexus;
    requires build.jenesis.repository.oidc;
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.usage;
    requires spring.boot;
    requires spring.context;
}
