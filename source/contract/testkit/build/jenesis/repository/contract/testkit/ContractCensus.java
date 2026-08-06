package build.jenesis.repository.contract.testkit;

import module java.base;

/**
 * Completeness ratchet shared by parameterized SPI contract suites.
 *
 * <p>A valid census has three independent sources of truth: provider classes parsed from source
 * {@code provides ... with ...} clauses, provider instances visible in the runtime module graph, and fixture or
 * reason-bearing exemption registrations. {@link #of} compares all three and throws {@link AssertionError} with every
 * discovered mismatch, keeping this helper independent of JUnit and assertion libraries.
 */
public final class ContractCensus {

    private static final int MODULE_DESCRIPTOR_LIMIT = 1_000_000;
    private static final Pattern PROVIDES = Pattern.compile(
            "\\bprovides\\s+([\\w.$]+)\\s+with\\s+([^;]+);", Pattern.DOTALL);
    private static final Pattern COMMENTS = Pattern.compile("//[^\\r\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * One provider identified by its stable selection name and implementation class name.
     */
    public record Provider(String name, String implementation) {

        public Provider {
            name = required(name, "provider name");
            implementation = required(implementation, "provider implementation");
        }

        /**
         * Describes a runtime provider without retaining its mutable instance.
         */
        public static Provider runtime(String name, Object provider) {
            Objects.requireNonNull(provider, "provider");
            return new Provider(name, provider.getClass().getName());
        }
    }

    /**
     * A temporary fixture exemption. The reason is mandatory so a waiver is always reviewable.
     */
    public record Exemption(String implementation, String reason) {

        public Exemption {
            implementation = required(implementation, "exempt provider implementation");
            reason = required(reason, "exemption reason");
        }
    }

    private final Class<?> service;
    private final List<Provider> declaredProviders;
    private final List<Provider> runtimeProviders;
    private final List<String> fixtureProviders;
    private final List<Exemption> exemptions;

    private ContractCensus(Class<?> service,
                           Collection<Provider> declaredProviders,
                           Collection<Provider> runtimeProviders,
                           Collection<String> fixtureProviders,
                           Collection<Exemption> exemptions) {
        this.service = Objects.requireNonNull(service, "service");
        this.declaredProviders = List.copyOf(declaredProviders);
        this.runtimeProviders = List.copyOf(runtimeProviders);
        this.fixtureProviders = fixtureProviders.stream()
                .map(provider -> required(provider, "fixture provider implementation")).toList();
        this.exemptions = List.copyOf(exemptions);
    }

    /**
     * Verifies one service census and returns its immutable inputs when they agree.
     *
     * @throws AssertionError if declarations, runtime discovery, fixtures, or exemptions disagree
     */
    public static ContractCensus of(Class<?> service,
                                    Collection<Provider> declaredProviders,
                                    Collection<Provider> runtimeProviders,
                                    Collection<String> fixtureProviders,
                                    Collection<Exemption> exemptions) {
        ContractCensus census = new ContractCensus(service, declaredProviders, runtimeProviders, fixtureProviders,
                exemptions);
        census.verify();
        return census;
    }

    /**
     * Parses every provider class declared for {@code service} below {@code sourceRoot}. Provider names initially use
     * the implementation class name; a suite with a domain selection name may replace them before calling {@link #of}.
     * Multiline provider lists are supported.
     */
    public static List<Provider> declaredProviders(Path sourceRoot, Class<?> service) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(service, "service");
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("SPI census source root is not a directory: " + sourceRoot);
        }
        List<Provider> providers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files
                    .filter(path -> path.getFileName().toString().equals("module-info.java"))::iterator) {
                Matcher matcher = PROVIDES.matcher(COMMENTS.matcher(readModuleDescriptor(file)).replaceAll(""));
                while (matcher.find()) {
                    if (!matcher.group(1).equals(service.getName())) {
                        continue;
                    }
                    for (String implementation : matcher.group(2).split(",")) {
                        String className = implementation.strip();
                        providers.add(new Provider(className, className));
                    }
                }
            }
        }
        providers.sort(Comparator.comparing(Provider::implementation));
        return List.copyOf(providers);
    }

    private static String readModuleDescriptor(Path file) throws IOException {
        StringBuilder descriptor = new StringBuilder();
        char[] buffer = new char[8_192];
        try (Reader reader = Files.newBufferedReader(file)) {
            for (int read; (read = reader.read(buffer)) != -1; ) {
                if (descriptor.length() + read > MODULE_DESCRIPTOR_LIMIT) {
                    throw new IOException("module descriptor exceeds " + MODULE_DESCRIPTOR_LIMIT + " characters: "
                            + file);
                }
                descriptor.append(buffer, 0, read);
            }
        }
        return descriptor.toString();
    }

    public Class<?> service() {
        return service;
    }

    public List<Provider> declaredProviders() {
        return declaredProviders;
    }

    public List<Provider> runtimeProviders() {
        return runtimeProviders;
    }

    public List<String> fixtureProviders() {
        return fixtureProviders;
    }

    public List<Exemption> exemptions() {
        return exemptions;
    }

    private void verify() {
        List<String> errors = new ArrayList<>();
        if (declaredProviders.isEmpty()) {
            errors.add("static graph declares no providers");
        }
        duplicates("static provider name", declaredProviders.stream().map(Provider::name).toList(), errors);
        duplicates("static provider class", declaredProviders.stream().map(Provider::implementation).toList(), errors);
        duplicates("runtime provider name", runtimeProviders.stream().map(Provider::name).toList(), errors);
        duplicates("runtime provider class", runtimeProviders.stream().map(Provider::implementation).toList(), errors);
        duplicates("fixture provider class", fixtureProviders, errors);
        duplicates("exempt provider class", exemptions.stream().map(Exemption::implementation).toList(), errors);

        Set<String> declared = declaredProviders.stream()
                .map(Provider::implementation).collect(Collectors.toCollection(TreeSet::new));
        Set<String> runtime = runtimeProviders.stream()
                .map(Provider::implementation).collect(Collectors.toCollection(TreeSet::new));
        Set<String> fixtures = new TreeSet<>(fixtureProviders);
        Map<String, String> exempt = exemptions.stream().collect(Collectors.toMap(
                Exemption::implementation, Exemption::reason, (first, _) -> first, TreeMap::new));

        difference(declared, runtime).forEach(provider ->
                errors.add("runtime graph does not discover statically declared provider " + provider));
        difference(runtime, declared).forEach(provider ->
                errors.add("static graph does not declare runtime provider " + provider));
        difference(declared, union(fixtures, exempt.keySet())).forEach(provider ->
                errors.add("declared provider has neither fixture nor exemption " + provider));
        difference(fixtures, declared).forEach(provider ->
                errors.add("fixture names no live statically declared provider " + provider));
        difference(exempt.keySet(), declared).forEach(provider ->
                errors.add("exemption names no live statically declared provider " + provider));
        intersection(fixtures, exempt.keySet()).forEach(provider ->
                errors.add("exemption is stale because a fixture exists for " + provider));

        if (!errors.isEmpty()) {
            throw new AssertionError("Contract census for " + service.getName() + " failed:\n - "
                    + String.join("\n - ", errors));
        }
    }

    private static void duplicates(String label, Collection<String> values, Collection<String> errors) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicate = new TreeSet<>();
        for (String value : values) {
            if (!seen.add(value)) {
                duplicate.add(value);
            }
        }
        duplicate.forEach(value -> errors.add("duplicate " + label + " " + value));
    }

    private static Set<String> difference(Collection<String> left, Collection<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(new HashSet<>(right));
        return result;
    }

    private static Set<String> intersection(Collection<String> left, Collection<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.retainAll(new HashSet<>(right));
        return result;
    }

    private static Set<String> union(Collection<String> left, Collection<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static String required(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
