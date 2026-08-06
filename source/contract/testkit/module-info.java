/**
 * Assertion-library-free support for SPI contract suites. {@link
 * build.jenesis.repository.contract.testkit.ContractCensus} compares provider declarations, the providers visible in
 * a bundle-backed runtime graph, and the fixtures or justified exemptions that cover them. It also parses multiline
 * {@code provides ... with ...} clauses so a provider module omitted from the test graph cannot disappear from both
 * sides of a ServiceLoader-only census.
 *
 * <p>The module is test support, not a production registry: it is {@code java.base}-only, owns no service lifecycle,
 * and performs no discovery unless a test explicitly supplies a source tree or provider collection.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.contract.testkit {
    exports build.jenesis.repository.contract.testkit;
}
