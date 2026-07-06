package build.jenesis.repository.ui.browser.test;

import module java.base;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;

/**
 * A thin driver wrapper over the console under test: navigation relative to the booted base URL, form login, and the
 * explicit polling helpers that stand in for {@code selenium-support}'s {@code WebDriverWait} /
 * {@code ExpectedConditions} (that module is excluded from the closure because its only extra transitive is the
 * automatic-module Guava - see this package's {@code module-info}). The waits are the handful the browser specs need:
 * an element present-and-displayed, an element clickable, a URL predicate, and page text - each a short poll that
 * tolerates the {@code StaleElementReferenceException} an htmx swap can throw mid-read.
 */
final class ConsoleBrowser {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(200);

    private final RemoteWebDriver driver;
    private final String baseUrl;

    ConsoleBrowser(RemoteWebDriver driver, String baseUrl) {
        this.driver = driver;
        this.baseUrl = baseUrl;
    }

    /** The underlying driver, for the rare direct call (a reload, the JavaScript executor). */
    RemoteWebDriver remote() {
        return driver;
    }

    /** Navigate to a console-relative path (leading slash) and return this for chaining. */
    ConsoleBrowser open(String path) {
        driver.get(baseUrl + path);
        return this;
    }

    String url() {
        return driver.getCurrentUrl();
    }

    /** The visible text of the whole document body - convenient for coarse assertions. */
    String bodyText() {
        return waitFor(By.tagName("body")).getText();
    }

    /** Form login through the {@code /login} page from a clean session, then wait until it has left the login page.
     *  Cookies are cleared first so a prior test's session never leaves the form in an authenticated state - each
     *  call starts unauthenticated, which is what makes the specs order-independent. */
    void login(String username, String password) {
        driver.get(baseUrl + "/login");
        driver.manage().deleteAllCookies();
        driver.get(baseUrl + "/login");
        type(By.name("username"), username);
        type(By.name("password"), password);
        clickable(By.cssSelector("button[type=submit], input[type=submit]")).click();
        waitForUrl(url -> !url.contains("/login"));
    }

    /** Wait for a displayed, enabled field matching {@code by}, clear it and type {@code text} into it - all against
     *  the one interactable element, so an ambiguous name that also matches a hidden input never trips over it. */
    WebElement type(By by, CharSequence text) {
        WebElement field = clickable(by);
        field.clear();
        field.sendKeys(text);
        return field;
    }

    // --- explicit waits (the selenium-support stand-ins) ---

    /** Poll until {@code condition} holds or the timeout elapses. */
    void waitUntil(String describe, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (StaleElementReferenceException | NoSuchElementException e) {
                last = e;
            }
            sleep();
        }
        throw new AssertionError("Timed out after " + TIMEOUT + " waiting for: " + describe, last);
    }

    /** Wait until an element matching {@code by} is present and displayed, and return it. */
    WebElement waitFor(By by) {
        WebElement[] found = new WebElement[1];
        waitUntil("a displayed element " + by, () -> {
            List<WebElement> elements = driver.findElements(by);
            for (WebElement element : elements) {
                if (element.isDisplayed()) {
                    found[0] = element;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    /** Wait until an element matching {@code by} is present, displayed and enabled, and return it. */
    WebElement clickable(By by) {
        WebElement[] found = new WebElement[1];
        waitUntil("a clickable element " + by, () -> {
            List<WebElement> elements = driver.findElements(by);
            for (WebElement element : elements) {
                if (element.isDisplayed() && element.isEnabled()) {
                    found[0] = element;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    /** Wait until the current URL satisfies {@code predicate}. */
    void waitForUrl(Predicate<String> predicate) {
        waitUntil("url predicate (was " + safeUrl() + ")", () -> predicate.test(driver.getCurrentUrl()));
    }

    /** Wait until the document's visible text contains {@code text}. */
    void waitForText(String text) {
        waitUntil("body text to contain '" + text + "'", () -> {
            List<WebElement> body = driver.findElements(By.tagName("body"));
            return !body.isEmpty() && body.getFirst().getText().contains(text);
        });
    }

    /** Run JavaScript in the page and return its result (the driver is a {@code JavascriptExecutor}). */
    Object script(String javascript, Object... arguments) {
        return driver.executeScript(javascript, arguments);
    }

    boolean present(By by) {
        return !driver.findElements(by).isEmpty();
    }

    List<WebElement> all(By by) {
        return driver.findElements(by);
    }

    private String safeUrl() {
        try {
            return driver.getCurrentUrl();
        } catch (RuntimeException _) {
            return "<unknown>";
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while polling");
        }
    }
}
