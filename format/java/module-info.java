/**
 * The shared Java repository-layout primitives the Maven and Jenesis layout formats build on: reading a jar's module
 * name and mapping between a module name and a Maven coordinate ({@link build.jenesis.repository.format.java.JavaLayout}),
 * and generating a POM ({@link build.jenesis.repository.format.java.PomGenerator}). It also carries the cross-publish
 * bridge ({@link build.jenesis.repository.format.java.bridge}) - the contract by which the two layouts publish each
 * other's view - exported only to those two modules, so cross-publishing stays off the public {@code RepositoryFormat}
 * SPI. Neither the SPI nor any other format sees the bridge.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format.java {
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.format.java;
    exports build.jenesis.repository.format.java.bridge
            to build.jenesis.repository.format.maven, build.jenesis.repository.format.jenesis;
}
