package build.jenesis.repository.store;

import module java.base;

/**
 * The config-driven enable/disable convention for every discovered SPI implementation, defined once here in the
 * base SPI module and reused verbatim by every distribution - a feature keeps the same key whether it ships in the
 * free image or a commercial one, and relocating a component between them never changes its configuration.
 *
 * <p>The convention, over the shared {@code jenesis.repository.*} namespace:
 * <ul>
 * <li>A <em>parallel</em> SPI (many implementations active at once - formats, import sources, feeds, gate
 *     policies, maintenance tasks) toggles each implementation with {@code jenesis.repository.<feature>=true|false},
 *     where {@code <feature>} is the provider's {@code name()}. Nothing set means <em>enabled</em>; only an explicit
 *     {@code false} disables. A disabled implementation is simply not activated at {@link ServiceLoader} discovery,
 *     so it degrades exactly like a missing module (its endpoint answers {@code 501} / not-found, the rest runs).</li>
 * <li>An <em>exclusive</em> SPI (one active implementation - the store backend, the token exchange) selects its
 *     implementation with {@code jenesis.repository.<spi>=<feature>}; nothing set picks the most universally
 *     applicable default (the {@code filesystem} store) or the first enabled implementation in discovery order.</li>
 * <li>An implementation's own settings live under {@code jenesis.<feature>.<property>=<value>} or its documented
 *     settings keys; they are never consulted here.</li>
 * <li><em>Required-config self-disable:</em> a provider declares the config keys it cannot run without (a
 *     credential, a bucket) through its {@code requiredConfig()}; a feature whose required keys are unset disables
 *     itself and logs one line saying which keys are missing and how to silence it.</li>
 * </ul>
 *
 * <p>The lookup is installed once at boot by the application shell ({@link #configure(UnaryOperator)}, handed the
 * Spring {@code Environment} so relaxed binding makes every key settable as an environment variable -
 * {@code JENESIS_REPOSITORY_<FEATURE>=false} in a plain {@code docker run -e}). Outside a shell the default lookup
 * reads system properties and then the environment under the same relaxed spelling, so a bare {@code ServiceLoader}
 * consumer honours the identical keys.
 */
public final class Features {

    private static final System.Logger LOGGER = System.getLogger(Features.class.getName());

    /** Namespace shared with the Spring property schema; a feature toggle is {@code jenesis.repository.<feature>}. */
    private static final String NAMESPACE = "jenesis.repository.";

    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private static volatile UnaryOperator<String> config = Features::defaults;

    private Features() {
    }

    /** Install the deployment's config lookup (the application shell hands in the Spring {@code Environment});
     *  until then, and after {@link #reset()}, system properties and environment variables answer directly. */
    public static void configure(UnaryOperator<String> lookup) {
        config = Objects.requireNonNull(lookup);
        ANNOUNCED.clear();
    }

    /** Restore the default system-property / environment lookup - the state before any {@link #configure}. */
    public static void reset() {
        configure(Features::defaults);
    }

    /** Whether the named feature is enabled: {@code jenesis.repository.<feature>} unset means enabled, and only an
     *  explicit {@code false} disables - so an image carrying every module runs everything until configured off. */
    public static boolean enabled(String feature) {
        return !"false".equalsIgnoreCase(config.apply(NAMESPACE + feature));
    }

    /** The implementation name an exclusive SPI is configured to ({@code jenesis.repository.<spi>=<feature>}), or
     *  empty when unset - the caller then applies its own most-universal default or discovery order. */
    public static Optional<String> selection(String spi) {
        String value = config.apply(NAMESPACE + spi);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /** Whether the named feature is {@link #enabled} <em>and</em> all its required config keys are set. A feature
     *  that is enabled but missing a required key (a credential, a bucket) disables itself and logs one line -
     *  naming the missing keys and the {@code jenesis.repository.<feature>=false} switch that silences it. */
    public static boolean active(String feature, Collection<String> requiredConfig) {
        if (!enabled(feature)) {
            return false;
        }
        List<String> missing = missing(requiredConfig, config);
        if (missing.isEmpty()) {
            return true;
        }
        if (ANNOUNCED.add(feature)) {
            LOGGER.log(System.Logger.Level.INFO, feature + " disabled - missing " + String.join(", ", missing)
                    + "; set " + NAMESPACE + feature + "=false to disable it and silence this.");
        }
        return false;
    }

    /** The keys of {@code requiredConfig} that are unset or blank in {@code lookup} - the shared check behind
     *  {@link #active} and an exclusive resolver's fail-loud path. */
    public static List<String> missing(Collection<String> requiredConfig, UnaryOperator<String> lookup) {
        List<String> missing = new ArrayList<>();
        for (String key : requiredConfig) {
            String value = lookup.apply(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }

    private static String defaults(String key) {
        String property = System.getProperty(key);
        if (property != null) {
            return property;
        }
        return System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
    }
}
