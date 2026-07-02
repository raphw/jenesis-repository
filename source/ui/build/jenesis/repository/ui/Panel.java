package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;

/**
 * A console panel, discovered with {@code ServiceLoader} and bridged into Spring by {@link UiConfig}. The shell ships
 * the core panel (browse); additional panels are registered by adding a provider module to the graph, with no fork of
 * the console. Each panel contributes a navigation entry and renders its body against the repository's
 * {@link ArtifactStore}, so a panel provider stays free of any Spring dependency while still reading real repository
 * data. The rendered body is a trusted HTML fragment the console drops into its Thymeleaf shell, so an implementation
 * escapes any repository-derived text it includes.
 */
public interface Panel {

    /** A stable id used in the navigation and as the in-page anchor. */
    String id();

    /** The navigation title. */
    String title();

    /** Render the panel body as an HTML fragment, reading the repository through the scoped {@code store}. */
    String render(ArtifactStore store) throws IOException;
}
