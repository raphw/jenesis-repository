/**
 * The shared Java repository-layout primitives the Maven and Jenesis layout formats build on: reading a jar's module
 * name and parsing a Maven request path ({@link build.jenesis.repository.format.java.JavaLayout}). It also carries the
 * cross-publish bridge ({@link build.jenesis.repository.format.java.bridge}) - the {@code ModuleView} contract by which
 * the Maven layout hands a published modular jar to the Jenesis layout for its module view - exported only to those two
 * modules, so cross-publishing stays off the public {@code RepositoryFormat} SPI. Neither the SPI nor any other format
 * sees the bridge.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format.java {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.format.java;
    exports build.jenesis.repository.format.java.bridge
            to build.jenesis.repository.format.maven, build.jenesis.repository.format.jenesis;
}
