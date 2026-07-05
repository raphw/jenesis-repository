package build.jenesis.repository.server;

import build.jenesis.repository.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The post-boot trigger for {@link DemoSeeder}: a shell wires one of these and calls {@link #start()} once the beans
 * are assembled. When demo mode is off it is inert; when on it runs the seeder on a background virtual thread and
 * returns at once, so seeding - a best-effort walk over the public registries - never blocks boot (the
 * {@code ImportJobs.submit} pattern). The seed only proceeds against a completely empty artifact space; a
 * {@code beforeSeed} hook (a deployment's demo gate config, say) runs first but only when a seed is actually about to
 * happen, so turning the flag on in a used deployment stays a harmless no-op that touches nothing.
 */
public final class DemoSeeding {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoSeeding.class);

    private final boolean enabled;
    private final DemoSeeder seeder;
    private final ArtifactStore store;
    private final Runnable beforeSeed;

    /** @param enabled whether the {@code demo} flag is on, {@code seeder} the collector/pull-through, {@code store}
     *  the already tenant-and-repository-scoped target space, and {@code beforeSeed} a hook run once, only when the
     *  space is empty and a seed is about to run (an edition layers its demo gate config here); pass a no-op when
     *  there is nothing to do first. */
    public DemoSeeding(boolean enabled, DemoSeeder seeder, ArtifactStore store, Runnable beforeSeed) {
        this.enabled = enabled;
        this.seeder = seeder;
        this.store = store;
        this.beforeSeed = beforeSeed;
    }

    /** Start demo seeding in the background when the flag is on; return at once so boot is never blocked. */
    public void start() {
        if (!enabled) {
            return;
        }
        Thread.ofVirtual().name("demo-seeder").start(this::run);
    }

    private void run() {
        try {
            if (!DemoSeeder.empty(store)) {
                LOGGER.info("Demo mode is on but the artifact space is not empty; nothing is seeded");
                return;
            }
            beforeSeed.run();
            seeder.seed(store);
        } catch (Exception exception) {
            // Demo seeding is best-effort; a failure is contained so it never destabilises a running server.
            LOGGER.warn("Demo seeding did not complete", exception);
        }
    }
}
