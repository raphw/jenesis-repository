/**
 * End-to-end test of the all-in-one composition: boots the exact module set the all-in-one image runs (the
 * {@code build.jenesis.repository.bundle} closure, through the same {@code AllInOne} launcher and the same
 * {@code allinone.properties}) and proves over raw HTTP that the one image is trimmed by configuration instead of
 * rebuilt - {@code jenesis.repository.<feature>=false} degrades a discovered implementation exactly like a missing
 * module (a disabled layout's path is unclaimed, a disabled fetcher answers the documented {@code 501}), and
 * {@code jenesis.repository.<spi>=<feature>} selects among exclusive implementations (a selection naming an
 * uninstalled fetcher degrades to {@code 501} rather than failing the boot). The same keys drive the enterprise
 * all-in-one image, asserted by its own bundle test over the identical spellings.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.bundle
 */
open module build.jenesis.repository.bundle.test {
    requires build.jenesis.repository.bundle;
    requires java.net.http;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
