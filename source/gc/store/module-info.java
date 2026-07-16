/**
 * The reference {@code GarbageCollector} ({@code mark-sweep}, discovered with {@code ServiceLoader}), riding the
 * shared artifact walk - never its own listing loop - so the mark over the caller's pointer roots and the sweep
 * over {@code blobs/} are both ordered, resumable, segmented and multi-node-safe. Sharded mark: references land as
 * append-only batch objects {@code gc/<pass>/refs/<hh>/...}, flushed before every walk checkpoint so no committed
 * cursor ever lies about an unflushed reference; sweep memory is one leading-byte shard at a time, O(N/256), never
 * an O(N) set. Condemn-then-collect: an unreferenced blob is first condemned ({@code gc/condemned/<hash>}, the
 * marker is the clock) and deleted only when a <em>later</em> pass confirms it still unreferenced - at least one
 * full collection interval of grace - with the marker re-read immediately before deletion and cleared on the write
 * path by every pointer link, so a referenced, re-linked or in-flight blob is never deleted. Settings:
 * {@code jenesis.gc.stride} (checkpoint stride of the collector's walk passes, default 20000).
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.gc.store {
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.walk;
    exports build.jenesis.repository.gc.store to build.jenesis.repository.gc.test;
    provides build.jenesis.repository.gc.GarbageCollectorProvider
            with build.jenesis.repository.gc.store.MarkSweepGarbageCollectorProvider;
}
