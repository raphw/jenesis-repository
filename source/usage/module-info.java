/**
 * Credential usage tracking as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.KeyUsageTrackerProvider} answering to {@code batching}, accumulating an
 * allowed request's tenant, key hash and source address on a bounded queue off the request path and flushing each
 * credential's count and last use through the authorization store at most once per day. A deployment without this
 * module records no usage and its health surface reports the worker as off.
 *
 * @jenesis.release 25
 * @jenesis.pin ch.qos.logback/logback-classic 1.5.34 SHA-256/b65e05076a5c1aadb659b4fe4bc5fee31cb26cd70390292eb03e4a7a24cff10f
 * @jenesis.pin ch.qos.logback/logback-core 1.5.34 SHA-256/42eda264c0c650c2bec59e66151a88b708a8663dc1b49d788202d53e78b8caae
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin commons-logging/commons-logging 1.3.5 SHA-256/6d7a744e4027649fbb50895df9497d109f98c766a637062fe8d2eabbb3140ba4
 * @jenesis.pin io.micrometer/micrometer-commons 1.17.0 SHA-256/03919dc71e2417ec4b5c254c4ba924963c972e124190f73cdcb68ed51c6eede6
 * @jenesis.pin io.micrometer/micrometer-core 1.17.0 SHA-256/73503e701a377fafeaf33b71b9b8910a8d7884cbba88ab27971b33b3753b65aa
 * @jenesis.pin io.micrometer/micrometer-jakarta9 1.17.0 SHA-256/4ae9dbc9072fea8c36684a745e0e944b9540fd15027dfe7af0a186f8df43272c
 * @jenesis.pin io.micrometer/micrometer-observation 1.17.0 SHA-256/2fc95a327578d3b2a81c3ff40e646a4a21e46b0153ccbbf91690142bf80d9661
 * @jenesis.pin jakarta.annotation/jakarta.annotation-api 3.0.0 SHA-256/b01f55552284cfb149411e64eabca75e942d26d2e1786b32914250e4330afaa2
 * @jenesis.pin org.apache.logging.log4j/log4j-api 2.25.4 SHA-256/c4b642a7f047275215de117e0e3847eb2c7711d84a0aa7433e7b3c096daf341d
 * @jenesis.pin org.apache.logging.log4j/log4j-to-slf4j 2.25.4 SHA-256/d7b78fc0aaaa5e8ada388b29d718b0ab187e512965bed0b259bb4ab299f13db2
 * @jenesis.pin org.apache.tomcat.embed/tomcat-embed-core 11.0.22 SHA-256/78cd7cd7c104b6b87142c1b0bd902e1ce005b0245c3cefa8a06759148947200b
 * @jenesis.pin org.apache.tomcat.embed/tomcat-embed-el 11.0.22 SHA-256/1b34c33b858c141df36c501b4d809e68036c406bca3671a86facae297917c7de
 * @jenesis.pin org.apache.tomcat.embed/tomcat-embed-websocket 11.0.22 SHA-256/210e0c7ab194a76cc7283df0be365276091b042369dae125fb477828ba67e922
 * @jenesis.pin org.apache.tomcat/tomcat-annotations-api 11.0.22 SHA-256/fbe1a2ee12ea472b773bf3a6237d95b66a002c5c4fa6d38a54e69c019558cfd4
 * @jenesis.pin org.hdrhistogram/HdrHistogram 2.2.2 SHA-256/22d1d4316c4ec13a68b559e98c8256d69071593731da96136640f864fa14fad8
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
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
 * @jenesis.pin org.springframework.boot/spring-boot-servlet 4.1.0 SHA-256/5f694a7c6c357a87032bc92db0e7e0e03b64010f77892e5992c86b10f568a5ae
 * @jenesis.pin org.springframework.boot/spring-boot-starter 4.1.0 SHA-256/40352b3fc0834d5830f66d40100fc8afa8ac73e24a134c7779bd42b72f2d6506
 * @jenesis.pin org.springframework.boot/spring-boot-starter-actuator 4.1.0 SHA-256/f6f6c4166430953515336ecf3b25ea467e24c4e5705cbcc8cefa273a4bd6bde8
 * @jenesis.pin org.springframework.boot/spring-boot-starter-jackson 4.1.0 SHA-256/4214c534cca5c7c7e1cf92db90f178a3dffdede503fb68ac3c0dd905f331431f
 * @jenesis.pin org.springframework.boot/spring-boot-starter-logging 4.1.0 SHA-256/73a6a42d2b6a589bd722aa107800829d0b20b731d94135f53c4b744db8beafbf
 * @jenesis.pin org.springframework.boot/spring-boot-starter-micrometer-metrics 4.1.0 SHA-256/ad4a34ba880e6a8c811e90c1c034b937b8a78030eae60ec5b43826c42590c802
 * @jenesis.pin org.springframework.boot/spring-boot-starter-security 4.1.0 SHA-256/5370ad6bd847e85675ee81a2da98f5fabcfc8649197b0a873417a051aa435c41
 * @jenesis.pin org.springframework.boot/spring-boot-starter-tomcat 4.1.0 SHA-256/3e8cbd141ee6f4f2acfaf6320f1951307816f086de426d08eac19ef4a57d7e90
 * @jenesis.pin org.springframework.boot/spring-boot-starter-tomcat-runtime 4.1.0 SHA-256/135fa0b7a4232c64b975f348cab39e2259d36a18e14768f5f213147a4febf68d
 * @jenesis.pin org.springframework.boot/spring-boot-starter-web 4.1.0 SHA-256/d2732bdc307d3628d680d32758b300972109f499ec8e023bd663cdad002c67c6
 * @jenesis.pin org.springframework.boot/spring-boot-tomcat 4.1.0 SHA-256/011e662eb6f9f4a80c5dacf914cfca8a25a33fcd753736ec453dcf701337dd24
 * @jenesis.pin org.springframework.boot/spring-boot-web-server 4.1.0 SHA-256/a8541ccbd29f5a8db7e6092fa83463aa4d1c002fac07b8b5babe118ad6c4a3d3
 * @jenesis.pin org.springframework.boot/spring-boot-webmvc 4.1.0 SHA-256/ab21735a550cbfefaa4ad6ffbb1a891592580ef05ad729cd2025bc0245862b55
 * @jenesis.pin org.springframework.security/spring-security-config 7.1.0 SHA-256/3234035bb5ccd45a9367ce526723d6b8da501c5c3f725b54a98354f922c2e978
 * @jenesis.pin org.springframework.security/spring-security-core 7.1.0 SHA-256/f8cecce9e65db9fe9ea42ca92b04d6e4e4320ff9d492aa60b753716ea397262c
 * @jenesis.pin org.springframework.security/spring-security-crypto 7.1.0 SHA-256/6f6957548a28451712e53b94a3e77057735b2fcec04c99ca6dd555b574453a98
 * @jenesis.pin org.springframework.security/spring-security-web 7.1.0 SHA-256/1deee612104ce85ec815076b80578cd8e82c07067e122068f09fbfef860b3cb1
 * @jenesis.pin org.springframework/spring-aop 7.0.8 SHA-256/1178f039e087884174e2affc46e484f4a8bd7f2a4e011d33dd9137709f740f80
 * @jenesis.pin org.springframework/spring-beans 7.0.8 SHA-256/6ec2e361a8872a71d8b1ff66f1bcb8cfa29fcc437931998919da7cecfb59b45b
 * @jenesis.pin org.springframework/spring-context 7.0.8 SHA-256/1eb7d552414ebac00e30ab3e809138d810785f6d2c4271db77cdf0181f308f19
 * @jenesis.pin org.springframework/spring-core 7.0.8 SHA-256/726ba2a5130833644bdf267a55ff26e1f52e8dcc9aa1ffa06904ca9c14619f25
 * @jenesis.pin org.springframework/spring-expression 7.0.8 SHA-256/3c97c38ab59c77ee886e08ccf8096f6bb58a1245f68dfed7a40e93f41c435f9a
 * @jenesis.pin org.springframework/spring-web 7.0.8 SHA-256/4d4ed7ecb0453d25d735ea27d025ea36b003c3d29cb7d006bedd6d5188a2f5c0
 * @jenesis.pin org.springframework/spring-webmvc 7.0.8 SHA-256/48f7e1e2d0d46e98ed3fa30d5a64cb1f7ed2aa339a82edcd87289ed8ff216f04
 * @jenesis.pin org.yaml/snakeyaml 2.6 SHA-256/c8f7a98e7394adda02f6317249710e4d1b4c7a25aa8c7eace0c2eea52eb8bf85
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.0 SHA-256/5e353ce53c6901105dfcbf183e3220c17072e334e552b818a4bb1b99decea596
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.0 SHA-256/3ef94a3dddeafc247c50230fad0315981b2ce4ae6e91cfb4368a86f328904e4f
 */
module build.jenesis.repository.usage {
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.observation;
    exports build.jenesis.repository.usage to build.jenesis.repository.test, build.jenesis.repository.usage.test;
    provides build.jenesis.repository.server.KeyUsageTrackerProvider
            with build.jenesis.repository.usage.BatchingKeyUsageTrackerProvider;
}
