package build.jenesis.repository;

import module java.base;
import build.jenesis.repository.source.ImportSource;
import build.jenesis.repository.source.Json;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Runs a migration as a background job so the trigger can return at once and the caller polls for progress. A
 * submitted import runs on a virtual thread and records its state - {@code running}, {@code completed} or
 * {@code failed}, with the running counts and the resume cursor - as a small JSON object in the store under
 * {@code imports/<id>}, which is what a status read returns. Persisting the cursor after each batch makes the
 * migration resumable: a re-submit naming a prior job continues its walk from the recorded cursor and counts, and
 * the content-addressed store dedupes anything a resumed run repeats. The
 * store is the only state, so progress survives a restart and a status read needs no in-memory registry.
 */
public final class ImportJobs {

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Start an import in the background, seeded with the given counts (non-zero for a resume), and return at once. */
    public void submit(ArtifactStore store, ImportSource source, String jobId, int baseImported, int baseSkipped)
            throws IOException {
        write(store, jobId, "running", baseImported, baseSkipped, new LinkedHashSet<>(), null, null);
        Thread.ofVirtual().name("import-" + jobId).start(() -> run(store, source, jobId, baseImported, baseSkipped));
    }

    private void run(ArtifactStore store, ImportSource source, String jobId, int baseImported, int baseSkipped) {
        AtomicInteger imported = new AtomicInteger(baseImported);
        AtomicInteger skipped = new AtomicInteger(baseSkipped);
        Set<String> skippedFormats = new LinkedHashSet<>();
        String[] cursor = {null};
        try {
            new RepositoryImport().run(source, store, new RepositoryImport.Listener() {
                @Override
                public void imported() {
                    imported.incrementAndGet();
                }

                @Override
                public void skipped(String format) {
                    skipped.incrementAndGet();
                    skippedFormats.add(format);
                }

                @Override
                public void checkpoint(String reached) throws IOException {
                    cursor[0] = reached;
                    write(store, jobId, "running", imported.get(), skipped.get(), skippedFormats, reached, null);
                }
            });
            write(store, jobId, "completed", imported.get(), skipped.get(), skippedFormats, null, null);
        } catch (Exception e) {
            try {
                write(store, jobId, "failed", imported.get(), skipped.get(), skippedFormats, cursor[0],
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (IOException suppressed) {
                throw new UncheckedIOException(suppressed);
            }
        }
    }

    /** The persisted state of a job as raw JSON bytes, or empty if there is no such job. */
    public Optional<byte[]> status(ArtifactStore store, String jobId) throws IOException {
        if (!store.exists("imports/" + jobId)) {
            return Optional.empty();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("imports/" + jobId, out);
        return Optional.of(out.toByteArray());
    }

    /** A job's state parsed for a status response or to seed a resume. */
    public Optional<Snapshot> snapshot(ArtifactStore store, String jobId) throws IOException {
        Optional<byte[]> bytes = status(store, jobId);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> state = Json.object(Json.parse(new String(bytes.get(), StandardCharsets.UTF_8)));
        List<String> formats = new ArrayList<>();
        for (Object format : Json.array(state.get("skippedFormats"))) {
            formats.add(Json.string(format));
        }
        return Optional.of(new Snapshot(Json.string(state.get("state")), Json.integer(state.get("imported")),
                Json.integer(state.get("skipped")), formats, Json.string(state.get("cursor")),
                Json.string(state.get("error"))));
    }

    private void write(ArtifactStore store, String jobId, String state, int imported, int skipped,
                       Set<String> skippedFormats, String cursor, String error) throws IOException {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("state", state);
        job.put("imported", imported);
        job.put("skipped", skipped);
        job.put("skippedFormats", new ArrayList<>(skippedFormats));
        job.put("cursor", cursor);
        job.put("error", error);
        store.write("imports/" + jobId, new ByteArrayInputStream(Json.write(job).getBytes(StandardCharsets.UTF_8)));
    }

    /** A parsed view of a job's persisted state. */
    public record Snapshot(String state, int imported, int skipped, List<String> skippedFormats,
                           String cursor, String error) {
    }
}
