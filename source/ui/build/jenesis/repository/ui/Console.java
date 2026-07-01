package build.jenesis.repository.ui;

import module java.base;
import com.sun.net.httpserver.HttpServer;

/**
 * The console shell: a minimal HTTP server that renders the registered panels (ServiceLoader over
 * {@link Panel}) as a tabbed page. The core panels ship by default; additional panels appear
 * automatically when their modules are on the graph. A skeleton.
 */
public final class Console {

    public static void main(String[] args) throws IOException {
        String configured = System.getProperty("JENESIS_UI_PORT", System.getenv("JENESIS_UI_PORT"));
        int port = Integer.parseInt(configured == null || configured.isBlank() ? "8081" : configured);
        List<Panel> panels = new ArrayList<>();
        ServiceLoader.load(Panel.class).forEach(panels::add);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            byte[] body = page(panels).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("jenesis-repository console on :" + port + " with " + panels.size() + " panel(s)");
    }

    private static String page(List<Panel> panels) {
        StringBuilder nav = new StringBuilder(), body = new StringBuilder();
        for (Panel panel : panels) {
            nav.append("<li><a href=\"#").append(panel.id()).append("\">").append(panel.title()).append("</a></li>");
            body.append("<section id=\"").append(panel.id()).append("\"><h2>").append(panel.title())
                    .append("</h2>").append(panel.render()).append("</section>");
        }
        return "<!doctype html><html><head><title>jenesis-repository</title></head><body>"
                + "<h1>jenesis-repository</h1><nav><ul>" + nav + "</ul></nav>" + body + "</body></html>";
    }
}
