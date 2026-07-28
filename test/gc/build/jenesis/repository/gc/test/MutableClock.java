package build.jenesis.repository.gc.test;

import module java.base;

/**
 * A hand-advanced {@link Clock}, so claim-expiry tests move time past a lease deterministically instead of
 * sleeping through a real TTL.
 */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-16T00:00:00Z"));

    void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
