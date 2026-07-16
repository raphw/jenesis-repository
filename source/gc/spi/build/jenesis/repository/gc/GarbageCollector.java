package build.jenesis.repository.gc;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * Reclaims content blobs ({@code blobs/<hash>}) that no live pointer references any more - the residue of a
 * republish, an eviction, a rejected upload or an abandoned staging deploy. Deletion is the one unrecoverable act
 * in the product, so the contract is safety-first: an implementation must never delete a blob that is referenced,
 * that was re-linked at any point up to its final pre-delete check (the dedup re-publish race - identical content
 * is stored once, so a "new" publish may link a blob the collector already judged unreferenced), or that is
 * younger than one collection interval (an in-flight publish stores the blob before its pointer links). Sparing an
 * orphan for another pass is always acceptable; the reverse never is.
 *
 * <p><b>What counts as referenced</b> is layout knowledge the caller owns: {@code pointerRoots} names the
 * top-level key prefixes whose small leaf objects hold a referenced blob's hash - always {@code publish} (the
 * content-addressed publication namespace), plus every root a blobs-namespace format declares for its own
 * pointers. A root missing from the list makes its blobs invisible to the reference scan and eligible for
 * reclamation, so the caller must name every one.
 *
 * <p>Mirrors the retention sweeper's shape: {@link #plan} computes what would be reclaimed right now without
 * writing anything - the dry run a maintenance console previews - and {@link #collect} computes and applies. Both
 * run over arbitrarily large stores, so an implementation enumerates through the shared artifact walk (resumable,
 * segmented, multi-node-safe), never a private full listing.
 */
public interface GarbageCollector {

    /**
     * The dry run: what {@link #collect} would reclaim right now, judged from the durable bookkeeping of earlier
     * passes. Writes nothing - not a marker, not a checkpoint - so it is always safe to preview. On a store where
     * no collection ever ran there is no earlier judgment and the plan is empty (with
     * {@link GcPlan#complete()} {@code false}): a first {@code collect} only condemns, it never deletes.
     */
    GcPlan plan(ArtifactStore store, List<String> pointerRoots, Instant now) throws IOException;

    /**
     * Run one collection pass and apply it: judge every blob against the live pointers under
     * {@code pointerRoots}, remember the unreferenced ones, and delete only what an <em>earlier</em> pass already
     * judged unreferenced and this pass confirms still is - at least one full collection interval of grace for
     * every crash-torn or in-flight publish. Returns what happened; a pass that could not finish (another node
     * still holds part of the shared enumeration) reports {@link GcPlan#complete()} {@code false} and has deleted
     * nothing it was not entitled to.
     */
    GcPlan collect(ArtifactStore store, List<String> pointerRoots, Instant now) throws IOException;
}
