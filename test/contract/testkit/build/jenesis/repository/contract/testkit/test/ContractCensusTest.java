package build.jenesis.repository.contract.testkit.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractCensusTest {

    interface Service {
    }

    static final class One implements Service {
    }

    static final class Two implements Service {
    }

    @TempDir
    Path root;

    @Test
    void a_complete_census_passes_without_an_assertion_library() {
        assertThatCode(() -> ContractCensus.of(Service.class,
                List.of(provider("one", One.class), provider("two", Two.class)),
                List.of(runtime("one", new One()), runtime("two", new Two())),
                List.of(One.class.getName()),
                List.of(new Exemption(Two.class.getName(), "the second provider has no observable contract yet"))))
                .doesNotThrowAnyException();
    }

    @Test
    void a_runtime_provider_missing_from_the_static_graph_trips_only_the_static_check() {
        assertThatThrownBy(() -> ContractCensus.of(Service.class,
                List.of(provider("one", One.class)),
                List.of(runtime("one", new One()), runtime("two", new Two())),
                List.of(One.class.getName()),
                List.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("static graph does not declare runtime provider " + Two.class.getName())
                .hasMessageNotContaining("runtime graph does not discover");
    }

    @Test
    void a_declared_provider_missing_from_the_runtime_graph_trips_only_the_runtime_check() {
        assertThatThrownBy(() -> ContractCensus.of(Service.class,
                List.of(provider("one", One.class), provider("two", Two.class)),
                List.of(runtime("one", new One())),
                List.of(One.class.getName(), Two.class.getName()),
                List.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("runtime graph does not discover statically declared provider "
                        + Two.class.getName())
                .hasMessageNotContaining("neither fixture nor exemption");
    }

    @Test
    void an_unfixtured_declared_provider_trips_only_the_fixture_check() {
        assertThatThrownBy(() -> ContractCensus.of(Service.class,
                List.of(provider("one", One.class), provider("two", Two.class)),
                List.of(runtime("one", new One()), runtime("two", new Two())),
                List.of(One.class.getName()),
                List.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("declared provider has neither fixture nor exemption " + Two.class.getName())
                .hasMessageNotContaining("runtime graph does not discover")
                .hasMessageNotContaining("static graph does not declare");
    }

    @Test
    void duplicate_names_and_classes_fail_independently() {
        assertThatThrownBy(() -> ContractCensus.of(Service.class,
                List.of(provider("same", One.class), provider("same", Two.class)),
                List.of(runtime("one", new One()), runtime("two", new Two())),
                List.of(One.class.getName(), Two.class.getName()),
                List.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicate static provider name same");

        assertThatThrownBy(() -> ContractCensus.of(Service.class,
                List.of(provider("first", One.class), provider("again", One.class)),
                List.of(runtime("one", new One())),
                List.of(One.class.getName()),
                List.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicate static provider class " + One.class.getName());
    }

    @Test
    void stale_fixture_and_exemption_registrations_fail() {
        assertThatThrownBy(() -> ContractCensus.of(Service.class,
                List.of(provider("one", One.class)),
                List.of(runtime("one", new One())),
                List.of(One.class.getName(), Two.class.getName()),
                List.of(new Exemption(One.class.getName(), "temporarily waived"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fixture names no live statically declared provider " + Two.class.getName())
                .hasMessageContaining("exemption is stale because a fixture exists for " + One.class.getName());
    }

    @Test
    void multiline_provider_lists_are_parsed_from_module_descriptors() throws IOException {
        Path module = Files.createDirectories(root.resolve("source/example"));
        Files.writeString(module.resolve("module-info.java"), """
                module example {
                    provides %s
                            with %s,
                                    %s;
                }
                """.formatted(Service.class.getName(), One.class.getName(), Two.class.getName()));

        assertThat(ContractCensus.declaredProviders(root.resolve("source"), Service.class))
                .extracting(Provider::implementation)
                .containsExactly(One.class.getName(), Two.class.getName());
    }

    private static Provider provider(String name, Class<?> implementation) {
        return new Provider(name, implementation.getName());
    }

    private static Provider runtime(String name, Object implementation) {
        return Provider.runtime(name, implementation);
    }
}
