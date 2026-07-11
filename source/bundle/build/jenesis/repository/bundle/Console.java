package build.jenesis.repository.bundle;

import build.jenesis.repository.ui.Application;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Boots the web console off the all-in-one module path, mirroring {@link AllInOne} for the console node: the same
 * image runs the console with {@code MAINCLASS=build.jenesis.repository.bundle.Console}, reading the bundle's
 * {@code allinone-console.properties} (a copy of the console's configuration) instead of the ambiguous root
 * {@code application.properties}. The console reads the same store the server writes ({@code JENESIS_STORE} /
 * {@code JENESIS_STORE_ROOT}), so both nodes off one image see one deployment.
 */
public final class Console {

    private Console() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(Application.class)
                .properties("spring.config.name=allinone-console")
                .run(args);
    }
}
