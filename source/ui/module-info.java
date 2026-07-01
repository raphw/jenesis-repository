/**
 * The simple, extendable web console: browse and search artifacts, view repositories and their config.
 * Built as an open shell with a panel-registration SPI ({@code uses Panel}), so additional panels are
 * registered by adding modules to the graph, with no fork of the console. A skeleton on the JDK HTTP server.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.repository.ui.Console
 */
module build.jenesis.repository.ui {
    requires build.jenesis.repository.store;
    requires jdk.httpserver;
    exports build.jenesis.repository.ui;
    uses build.jenesis.repository.ui.Panel;
    provides build.jenesis.repository.ui.Panel
            with build.jenesis.repository.ui.BrowsePanel;
}
