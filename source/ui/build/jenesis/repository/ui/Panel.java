package build.jenesis.repository.ui;

/**
 * A console panel, discovered with ServiceLoader. The shell ships the core panels (browse, search); additional
 * panels are registered by adding a provider, with no fork of the console. Each panel contributes a navigation
 * entry and renders its body.
 */
public interface Panel {

    /** A stable id used in the URL and navigation. */
    String id();

    /** The navigation title. */
    String title();

    /** Render the panel body as an HTML fragment. */
    String render();
}
