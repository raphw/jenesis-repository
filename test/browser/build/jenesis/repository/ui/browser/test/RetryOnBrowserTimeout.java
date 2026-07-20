package build.jenesis.repository.ui.browser.test;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.openqa.selenium.WebDriverException;

/**
 * Re-runs a browser test method up to {@link #MAX_ATTEMPTS} times, but ONLY when it failed with a transient
 * infrastructure timeout - a {@link BrowserTimeoutException} from a wait helper, or any Selenium
 * {@link WebDriverException} (which includes Selenium's own {@code TimeoutException}). This is the CI-minute saver:
 * the suite passes on quiet runs but progressively times out on the {@code /login} page when the Selenium/Chrome
 * container starves the runner mid-build, and a single starved spec used to force a whole-CI re-trigger. An in-run
 * retry absorbs that residual infra blip after the per-test fresh session (see {@code ConsoleBrowserTest}) has already
 * cut the shared-session degradation at its source.
 *
 * <p>A genuine product assertion ({@link AssertionError} / opentest4j failure) is NEVER retried - it fails fast on the
 * first attempt, so a real regression is surfaced immediately and never masked by a re-run.
 */
public final class RetryOnBrowserTimeout implements InvocationInterceptor {

    /** One initial run plus two retries. */
    static final int MAX_ATTEMPTS = 3;

    private static final Logger LOG = System.getLogger(RetryOnBrowserTimeout.class.getName());

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        String name = extensionContext.getDisplayName();
        // JUnit requires proceed()/skip() to be called exactly once on the supplied invocation, so the first attempt
        // drives it; any further attempt re-enters the test body reflectively on the same (fresh-per-test) instance.
        retry(name, new Body() {
            private boolean first = true;

            @Override
            public void run() throws Throwable {
                if (first) {
                    first = false;
                    invocation.proceed();
                } else {
                    reinvoke(invocationContext);
                }
            }
        });
    }

    /** Run {@code body} until it passes or attempts are exhausted, retrying only transient infra failures. */
    static void retry(String name, Body body) throws Throwable {
        Throwable last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                body.run();
                if (attempt > 1) {
                    LOG.log(Level.INFO, "Browser test {0} passed on retry {1}/{2}",
                            name, attempt - 1, MAX_ATTEMPTS - 1);
                }
                return;
            } catch (Throwable failure) {
                if (!isTransientInfrastructure(failure)) {
                    throw failure; // a product assertion / real failure: fail fast, never retried
                }
                last = failure;
                if (attempt < MAX_ATTEMPTS) {
                    LOG.log(Level.WARNING, "Browser test {0} hit a transient infra timeout ({1}); retrying ({2}/{3})",
                            name, failure.getClass().getSimpleName(), attempt, MAX_ATTEMPTS - 1);
                }
            }
        }
        throw last; // retries exhausted: surface the last transient failure so the lane still reports it
    }

    /** A wait-timeout ({@link BrowserTimeoutException}) or any Selenium {@link WebDriverException} (Selenium's own
     *  {@code TimeoutException} is a subtype) - anywhere in the cause chain - is transient infrastructure. A bare
     *  {@link AssertionError} matches neither, so a product assertion is deliberately not retryable. */
    static boolean isTransientInfrastructure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BrowserTimeoutException || t instanceof WebDriverException) {
                return true;
            }
        }
        return false;
    }

    private static void reinvoke(ReflectiveInvocationContext<Method> context) throws Throwable {
        Method method = context.getExecutable();
        method.setAccessible(true);
        Object target = context.getTarget().orElseThrow();
        List<Object> arguments = context.getArguments();
        try {
            method.invoke(target, arguments.toArray());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** A test body that can be run repeatedly - the first run drives JUnit's invocation, later runs re-enter it. */
    @FunctionalInterface
    interface Body {
        void run() throws Throwable;
    }
}
