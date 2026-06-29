package build.jenesis.repository.ui;

/** The core artifact-browse panel, shipped in the free console. A skeleton placeholder. */
public final class BrowsePanel implements Panel {

    @Override
    public String id() {
        return "browse";
    }

    @Override
    public String title() {
        return "Browse";
    }

    @Override
    public String render() {
        return "<p>Browse repositories and artifacts. (skeleton)</p>";
    }
}
