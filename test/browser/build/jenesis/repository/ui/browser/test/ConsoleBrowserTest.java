package build.jenesis.repository.ui.browser.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tag-gated browser suite over the repository console: one boot of the real {@link Console} plus one
 * {@code selenium/standalone-chrome} container drive the two behaviours the {@code ConsoleE2ETest} markup assertions
 * structurally cannot see, because they only execute in a DOM with JavaScript - the htmx lazy-tree expansion (a folder
 * toggle swaps its child rows in without a reload) and the {@code /js/theme.js} theme switch (a dark choice is applied
 * live and survives a reload via {@code localStorage}). Self-skips without Docker; a strict {@code jenesis.test.required}
 * lane fails instead.
 *
 * <p>The {@code dev} profile serves form login backed by an in-memory {@code admin}/{@code admin} account; the store is
 * seeded the way a deploy leaves it (a single published artifact, so the browse tree has a folder to expand). Each test
 * logs in fresh from a cleared session, so method order is irrelevant.
 */
@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
@ExtendWith(RetryOnBrowserTimeout.class)
public class ConsoleBrowserTest {

    private SeleniumContainer container;
    private Console console;
    private RemoteWebDriver driver;
    private ConsoleBrowser browser;

    @BeforeAll
    void boot() throws Exception {
        Requirement.requireOrSkip(SeleniumContainer.dockerAvailable(),
                "no Docker daemon for the selenium/standalone-chrome container");
        container = SeleniumContainer.start();
        console = Console.start();
        seed(console.storeRoot());
    }

    /** A FRESH browser session per test. The container and the server boot once (expensive), but each spec drives its
     *  own {@link RemoteWebDriver}: a single session shared across the whole ordered suite accumulates state and slows
     *  under CI load until a form login tips past the wait budget and the rest of the suite cascades - the "first spec
     *  passes, the rest time out" flake. A per-test session keeps every spec as fast as the first, and the specs are
     *  already session-independent (each logs in from a cleared session), so this is behaviour-neutral. */
    @BeforeEach
    void openBrowser() throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox", "--disable-gpu", "--window-size=1400,1000");
        driver = new RemoteWebDriver(container.webDriverUrl().toURL(), options);
        browser = new ConsoleBrowser(driver, console.baseUrl());
    }

    /** End this test's session on the grid so no per-test session accumulates on the container. Null the fields first
     *  and swallow a quit failure: a session already evicted under CI load must never mask the test's own outcome. */
    @AfterEach
    void closeBrowser() {
        RemoteWebDriver finished = driver;
        driver = null;
        browser = null;
        if (finished != null) {
            try {
                finished.quit();
            } catch (WebDriverException alreadyGone) {
                // the grid session was lost under load; nothing left to release
            }
        }
    }

    @AfterAll
    void shutdown() {
        if (console != null) {
            console.close();
        }
        if (container != null) {
            container.close();
        }
    }

    /** Publish a single artifact so the browse root has one folder ({@code maven}) to expand, with a deeper child
     *  ({@code org}) that only the lazy-children fetch brings into the DOM. No blob is opened by the console. */
    private static void seed(Path storeRoot) throws IOException {
        ArtifactStore backend = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? storeRoot.toString() : null);
        Publication publication = new Publication(backend);
        String blob = publication.storeBlob(
                new ByteArrayInputStream("a small library jar payload".getBytes(StandardCharsets.UTF_8)));
        publication.link("/maven/org/example/demo/1/demo-1.jar", blob);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // browse tree (htmx lazy children)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void a_the_browse_tree_lazily_swaps_child_rows_in_via_htmx() {
        browser.login("admin", "admin");
        browser.open("/browse");
        // The root lists exactly the one top-level folder 'maven'; 'org' is one level deeper and not yet in the DOM.
        browser.waitForText("maven");
        assertThat(browser.bodyText()).doesNotContain("org/");
        int before = browser.all(By.cssSelector("table.app-list tr")).size();
        // The toggle carries hx-get=/browse/children, hx-target="closest tr", hx-swap="afterend": htmx fetches the
        // folder's children and swaps them in after its row - 'org/' appears without a full-page navigation.
        browser.clickable(By.cssSelector("button.app-tree-toggle")).click();
        browser.waitForText("org/");
        assertThat(browser.all(By.cssSelector("table.app-list tr")).size())
                .as("child rows were swapped in, not navigated to").isGreaterThan(before);
        // A swap, not a navigation: the URL never left /browse.
        assertThat(browser.url()).endsWith("/browse");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // theme switch (theme.js persistence)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void b_the_theme_switch_persists_a_dark_choice_across_a_reload() {
        browser.login("admin", "admin");
        browser.open("/browse");
        // Start from a known "no explicit choice" state so the assertions are deterministic regardless of test order.
        browser.script("window.localStorage.removeItem('jenesis-theme');");
        browser.remote().navigate().refresh();
        browser.waitFor(By.cssSelector("select.app-theme-select[data-theme-select]"));
        assertThat(browser.script("return document.documentElement.getAttribute('data-theme');"))
                .as("no override yet, so the OS preference governs and no data-theme is set").isNull();

        // Flip the switch to Dark by clicking its option - a real change event, exercising the theme.js wiring.
        WebElement select = browser.waitFor(By.cssSelector("select.app-theme-select[data-theme-select]"));
        select.findElement(By.cssSelector("option[value='dark']")).click();
        browser.waitUntil("theme.js applies the dark override live", () ->
                "dark".equals(browser.script("return document.documentElement.getAttribute('data-theme');")));
        assertThat(browser.script("return window.localStorage.getItem('jenesis-theme');"))
                .as("the choice is persisted to localStorage under the shared key").isEqualTo("dark");

        // Reload: theme.js re-reads localStorage and re-applies the override before first paint, and re-selects the
        // control - the whole point of the pre-paint, non-deferred script the shell head loads.
        browser.remote().navigate().refresh();
        browser.waitFor(By.cssSelector("select.app-theme-select[data-theme-select]"));
        assertThat(browser.script("return document.documentElement.getAttribute('data-theme');"))
                .as("the dark override survives the reload").isEqualTo("dark");
        assertThat(browser.script("return window.localStorage.getItem('jenesis-theme');")).isEqualTo("dark");
        assertThat(browser.waitFor(By.cssSelector("select.app-theme-select[data-theme-select]")).getAttribute("value"))
                .as("the control reflects the persisted choice on load").isEqualTo("dark");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // mobile friendliness (W5.60): every screen at a phone-width viewport
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void c_no_screen_overflows_a_phone_viewport() {
        browser.resize(360, 800);
        try {
            // The login page itself, unauthenticated.
            browser.remote().manage().deleteAllCookies();
            assertNoHorizontalOverflow("/login");

            browser.login("admin", "admin");
            for (String path : List.of("/console", "/browse")) {
                assertNoHorizontalOverflow(path);
                // The shell survives the narrow viewport: the brand and the theme switch are still rendered.
                assertThat(browser.present(By.cssSelector(".app-brand"))).as("brand fits at 360px on " + path).isTrue();
                assertThat(browser.present(By.cssSelector("select.app-theme-select")))
                        .as("theme switch fits at 360px on " + path).isTrue();
            }

            // The browse table lives in a <figure> (the wide-table containment contract): a wide listing scrolls
            // inside its own container, never the page.
            browser.open("/browse");
            browser.waitFor(By.cssSelector("figure > table.app-list"));
        } finally {
            browser.resize(1400, 1000);
        }
    }

    /** Open {@code path} and assert no horizontal page overflow: the document is never wider than the viewport
     *  (a wide table scrolls inside its own {@code <figure>} instead - the containment contract). */
    private void assertNoHorizontalOverflow(String path) {
        browser.open(path);
        browser.waitFor(By.tagName("body"));
        assertThat(browser.url()).as("%s rendered, not redirected away", path).contains(path);
        Number scroll = (Number) browser.script("return document.documentElement.scrollWidth;");
        Number client = (Number) browser.script("return document.documentElement.clientWidth;");
        assertThat(scroll.longValue())
                .as("no horizontal page overflow on %s at a phone-width viewport", path)
                .isLessThanOrEqualTo(client.longValue());
    }
}
