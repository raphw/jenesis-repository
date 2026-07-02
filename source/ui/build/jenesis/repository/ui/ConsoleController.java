package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the console: it asks each registered {@link Panel} to render its body against the repository
 * {@link ArtifactStore} and drops the fragments into the Thymeleaf shell ({@code templates/console.html}) as a tabbed
 * page, so a plugged-in panel appears with no change here. The panels are the {@code ServiceLoader}-discovered list
 * bridged by {@link UiConfig}.
 */
@Controller
public class ConsoleController {

    private final List<Panel> panels;
    private final ArtifactStore store;

    public ConsoleController(List<Panel> panels, ArtifactStore store) {
        this.panels = panels;
        this.store = store;
    }

    /** The root forwards to the console so a bare host lands on it, keeping {@code /} free of a functional route. */
    @GetMapping("/")
    public String root() {
        return "redirect:/console";
    }

    @GetMapping("/console")
    public String console(Model model) throws IOException {
        List<RenderedPanel> rendered = new ArrayList<>();
        for (Panel panel : panels) {
            rendered.add(new RenderedPanel(panel.id(), panel.title(), panel.render(store)));
        }
        model.addAttribute("panels", rendered);
        return "console";
    }

    /** A panel prepared for the view: its id and title for the navigation, and its already-rendered HTML body. */
    public static final class RenderedPanel {

        private final String id;
        private final String title;
        private final String body;

        RenderedPanel(String id, String title, String body) {
            this.id = id;
            this.title = title;
            this.body = body;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getBody() {
            return body;
        }
    }
}
