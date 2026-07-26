package build.jenesis.repository.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.PublishInterceptor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test-only publication screen, discovered like a real one, that counts how many times the chain assesses a body -
 * but only for a path carrying the distinctive {@code count-me} token, so it is inert for every other test and the
 * count reflects exactly one test's screening. It always {@code ACCEPT}s, so it never changes a verdict; it exists so
 * a test can prove the ingress write edge screens a claimed body <em>exactly once</em> (an edge-screened, layout-only
 * format is screened once by the edge and not again by the format), and that a {@code screened()==false} format's
 * write bypasses the edge screen entirely (the counter stays zero).
 */
public final class CountingInterceptor implements PublishInterceptor {

    static final String MARKER = "count-me";

    private static final AtomicInteger COUNT = new AtomicInteger();

    static void reset() {
        COUNT.set(0);
    }

    static int count() {
        return COUNT.get();
    }

    @Override
    public Disposition assess(ArtifactDescriptor artifact, Content content) {
        String path = artifact.path();
        if (path != null && path.contains(MARKER)) {
            COUNT.incrementAndGet();
        }
        return Disposition.ACCEPT;
    }
}
