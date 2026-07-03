jenesis-repository
==================

A dual-layout artifact repository: it serves the same artifacts under both the
**Maven layout** (so any Maven, Gradle, or Jenesis Maven-mode build resolves them)
and the **Jenesis module layout** (so a `modular`/`modular_to_maven` build resolves
them by module name). Publish a module once and the server **computes its POM and
`maven-metadata.xml`** so both ecosystems can consume it from a single upload. It is
**also an OCI / Docker registry**, so `docker push` and `docker pull` work against the
very same store.

It is **free and open**, and **extensible by design**: every layout it speaks - the
Maven layout, the module layout, OCI/Docker, the raw layout - and every storage backend,
importer and console panel is a `ServiceLoader` plug-in over one content-addressed store,
with no core to fork (see *Extensible by design*, below).

A container registry, too
-------------------------

The same server is a standards-compliant **OCI registry** (the `/v2/` Distribution
API), so the Docker and OCI tooling talks to it directly, with no plugin or sidecar:

    docker tag my-app repo.example.com/my-app:1.0
    docker push repo.example.com/my-app:1.0
    docker pull repo.example.com/my-app:1.0

It implements the registry protocol end to end: monolithic **and** chunked blob
uploads, manifests addressed by tag or by digest (the media type kept in a sidecar so a
pull returns it verbatim), `tags/list`, and `HEAD` existence checks. It can also run as
a **pull-through mirror**: a manifest or blob miss is fetched from an upstream registry
(Docker Hub by default), verified by digest, stored, and re-served, following the
Distribution bearer-token flow and resolving multi-arch image indexes.

The fit is unusually clean because an OCI blob is addressed by its `sha256:<hex>`
digest, which is **exactly the content-addressed `blobs/<hex>` key the repository
already uses for everything else**. So image layers, configs and manifests dedupe
against each other and share storage with the rest of the repository (identical bytes
are stored once), and a container image inherits the same multi-tenancy, authorization,
storage and console as a Maven artifact, for free.

Extensible by design
--------------------

Everything the server speaks is a plug-in over one content-addressed store, discovered
with `ServiceLoader`. There is no central table of formats to edit and no core to fork;
a deployment simply runs whichever plug-ins are on its module path:

 - **Repository formats** (`RepositoryFormat`) - the wire protocol of one client
   ecosystem. The Maven layout, the Jenesis module layout, OCI/Docker and a raw/generic
   layout ship as modules; another ecosystem (npm, PyPI, NuGet, ...) is one more.
 - **Storage backends** (`ArtifactStoreProvider`, returning an `ArtifactStore`) -
   filesystem, S3 / GCS / MinIO and Azure Blob ship in the core; a new backend is a
   single provider.
 - **Importers** (`RepositoryImporter`) - migrate one ecosystem off an incumbent
   manager (see *Importing from another repository*, below).
 - **Import sources** (`ImportSourceProvider`) - connect to an incumbent manager and
   walk its assets; Nexus and Artifactory ship as modules, another incumbent is one more.
 - **Console panels** (`Panel`) - contribute a page to the web console.
 - **Pull-through proxying** (`ProxyFormat`) - an opt-in capability a format adds to
   mirror an upstream; the OCI format uses it to mirror Docker Hub.
 - **Upstream connectivity** (`FetcherProvider`) - the HTTP fetcher behind pull-through proxying and
   repository imports is a discovered module (`source/proxy`, providing `http` with index revalidation and
   negative caching); without it a deployment serves local content only and refuses imports.
 - **Workload token exchange** (`TokenExchangeProvider`) - exchanging a CI job's identity token for a
   short-lived credential is a discovered module (`source/oidc`, validating against the tenant's trust
   policy); the OAuth2/JOSE dependency stack lives there, not in the server.
 - **Credential usage tracking** (`KeyUsageTrackerProvider`) - stamping a credential's last use and count
   is a discovered module (`source/usage`, a batching off-request worker); without it nothing records and
   the worker reports as off.
 - **Rate limiting** (`RateLimiterProvider`) - the metering strategy behind the 429 filter is a discovered
   module (`source/ratelimit`, an in-memory token bucket); a coordinated limiter for a replicated deployment
   would be another module, and without one nothing is limited.
 - **Upload post-processing** (`PublishInterceptor`) - a hook run when an upload commits,
   after the blob is stored content-addressed but *before* its pointer is linked: it reads
   the neutral `ArtifactDescriptor` the format emits and returns `ACCEPT` / `QUARANTINE` /
   `REJECT`, so a quarantine gate, scanner or audit plugs in without any format knowing it.
   The core ships no interceptor, so every upload is accepted and served exactly as before;
   a commercial edition plugs its compliance gate in here.

A whole format is three methods over the already tenant-and-repository-scoped store:

    public interface RepositoryFormat {
        String name();                                              // "maven", "oci", "npm"
        boolean handles(String path);                               // does it own this request path?
        void handle(FormatExchange exchange, ArtifactStore store);  // serve or accept the request
    }

So a new ecosystem is a module that depends only on this SPI and the store, declares
`provides RepositoryFormat with ...`, and is discovered at startup - it inherits the
content-addressed storage, multi-tenancy, authorization and console without
touching any of them. The OCI/Docker registry is itself the proof: a single
self-contained module that needs nothing but the SPI and the store.

Two conventions keep this honest:

 - **The server names no plug-in.** The server module only `uses` the SPIs; it never
   `requires` a concrete format, backend or inspector. So a deployment runs a **plain
   server with exactly the layouts and backends on its module path** - nothing more.
   Adding one is dropping a jar on the path; removing one is leaving it off. The
   distribution (the image's module set) chooses; the core stays generic.
 - **An implementation lives under its SPI's package.** A format is
   `build.jenesis.repository.format.<name>` under the `build.jenesis.repository.format`
   SPI; a storage backend is `build.jenesis.repository.store.<name>` under the store SPI.
   The module name states which extension point it plugs into.

Writing a plug-in
-----------------

A plug-in is an ordinary module: implement the SPI, declare it with `provides ... with
...`, and put the module on the server's path. Nothing in the core changes - the server
already `uses` each SPI, so `ServiceLoader` finds the implementation at startup.

As a worked example, here is a tiny format ("file handler") that stores a file on `PUT`
and serves it back on `GET`, straight over the shared content-addressed store:

    // com/acme/files/FilesFormat.java
    package com.acme.files;

    import build.jenesis.repository.format.FormatExchange;
    import build.jenesis.repository.format.RepositoryFormat;
    import build.jenesis.repository.store.ArtifactStore;
    import java.io.IOException;
    import java.io.OutputStream;

    public final class FilesFormat implements RepositoryFormat {

        public String name() {
            return "files";
        }

        public boolean handles(String path) {        // the repository prefix is already stripped
            return path.startsWith("/files/");
        }

        public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
            String key = "files/" + exchange.path().substring("/files/".length());
            switch (exchange.method()) {
                case "PUT" -> {                                  // request body streams straight into the store
                    store.write(key, exchange.requestStream());
                    exchange.respond(201);
                }
                case "GET" -> {
                    if (!store.exists(key)) {
                        exchange.respond(404);
                    } else {                                     // and streams straight back out, never buffered
                        try (OutputStream out = exchange.respond(200, store.size(key))) {
                            store.read(key, out);
                        }
                    }
                }
                default -> exchange.respond(405);
            }
        }
    }

Register it as a service in the module descriptor. The module depends only on the
format SPI and the store, never on the server:

    // module-info.java
    module com.acme.files {
        requires build.jenesis.repository.format;
        requires build.jenesis.repository.store;
        provides build.jenesis.repository.format.RepositoryFormat
                with com.acme.files.FilesFormat;
    }

Then put the module on the server's module path (in a Jenesis build, register it as a
module and select it alongside `source+server`). On the next start the dispatcher
loads it, routes every `/files/...` request to it, and it inherits multi-tenancy,
authorization and the console untouched.

Every extension point works the same way - implement the interface, then `provides ...
with ...` it from a module on the path:

| Plug-in | Implement | `provides` service |
|---------|-----------|--------------------|
| A repository format / file handler | `RepositoryFormat`      | `build.jenesis.repository.format.RepositoryFormat`   |
| A storage backend                  | `ArtifactStoreProvider` | `build.jenesis.repository.store.ArtifactStoreProvider` |
| A migration importer               | `RepositoryImporter`    | `build.jenesis.repository.format.RepositoryImporter` |
| An import source (incumbent connector) | `ImportSourceProvider` | `build.jenesis.repository.importer.ImportSourceProvider` |
| A console panel                    | `Panel`                 | `build.jenesis.repository.ui.Panel`                  |
| An upload post-processor           | `PublishInterceptor`    | `build.jenesis.repository.store.PublishInterceptor`  |

A format may additionally implement one or both of two optional capabilities, detected
by `instanceof` so a format that has no use for them is unaffected: `ProxyFormat` to gain
pull-through mirroring (the OCI format does exactly that to mirror Docker Hub), and
`ArtifactLayout` to expose the neutral coordinate behind a request path - its
`{ecosystem, coordinate, version}` and prerelease flag, and the paths a version occupies -
so post-processing, inventory and cleanup key on the coordinate a format supplies rather
than parsing its layout.

Sits on cloud infra by design
-----------------------------

The server is **stateless**. Every byte it owns - artifacts, generated POMs,
checksums, and the `maven-metadata.xml` / module index - lives in **cloud object
storage** (S3, GCS, or Azure Blob), whose durability (eleven nines) and
availability (multi-AZ) are the managed cloud's job, not yours. So:

 - an instance dying loses nothing; the cloud restarts or replaces it;
 - it scales horizontally behind a load balancer or runs serverless (Cloud Run /
   ECS Fargate / Container Apps) with no sticky state;
 - you run it in your own cloud account, so durability and uptime are the managed
   cloud's, not an operations burden.

Streams end to end, never buffering a blob
------------------------------------------

An artifact moves **from the network straight to storage and back** - it is never held
whole in memory. An upload streams from the request body through the digest into the
store; a download streams from the store to the response. The heap cost of a transfer is
one fixed-size buffer, not the artifact, so a 4 KB POM and a 4 GB image layer cost the
same memory and a small, fixed heap serves arbitrarily large artifacts under arbitrary
concurrency. This is a structural property, not an optimization - it holds on every path:

 - **The store speaks streams.** `write(key, InputStream)` and `read(key, OutputStream)`
   copy through; `writeBlob(InputStream)` content-addresses a blob *as it streams*,
   digesting on the way to `blobs/<sha256>`. A backend that needs the length up front
   (S3 `PutObject`) spills to a temp file, never to the heap.
 - **Formats never see a `byte[]` body.** `FormatExchange` hands the request out as an
   `InputStream` and the response as an `OutputStream` - there is no "give me the whole
   body" call to reach for, so a plug-in streams by default.
 - **Even inspection streams.** Cross-publishing a modular jar into the module layout
   needs the jar's module name, so the Maven layout stores the blob first and reads the
   name back *from storage* rather than buffering the jar to look inside it.
 - **Chunked `docker push` streams.** Each uploaded chunk lands in storage as it
   arrives; finalizing concatenates the chunks as one stream into `writeBlob`, so a
   multi-hundred-megabyte layer never accumulates in memory.
 - **The pull-through mirror and the importers stream too.** An upstream miss or a
   migrated asset is copied from upstream to the store as a stream, digest and all.

The only bytes ever fully in memory are **small, bounded metadata** - a generated
`maven-metadata.xml`, a checksum, a manifest's media type, a compare-and-set pointer -
never an artifact, a layer or an image. Together with the statelessness above, this is
what lets the server run serverless in a tiny, fixed footprint.

Modules
-------

    source/                   all module sources (not a module itself)
      format/                 repository formats
        spi/                  the RepositoryFormat / RepositoryImporter SPIs
        java/ maven/ ...      the built-in layouts (java, maven, jenesis, oci, raw)
      store/                  storage backends
        spi/                  the ArtifactStore SPI + content-addressed Publication
        filesystem/           the default filesystem backend
        s3/                   S3-compatible backend (AWS S3, GCS, MinIO)
        azure/                Azure Blob backend
      importer/               import connectors (the read half of a migration)
        spi/                  the ImportSource SPI
        nexus/ artifactory/   the built-in connectors
      server/                 the dual-layout repository server (RepositoryApplication)
      ui/                     a simple, extendable web console (browse, repo config)
    test/                     tests, mirroring source/ (server/, store/s3, store/azure)

The console is an open shell with a **panel-registration SPI**, so additional panels
plug in through the console's extension points without forking the core.

| Module | Folder | What it is |
|--------|--------|------------|
| `build.jenesis.repository.store`    | `source/store/spi` | The `ArtifactStore` SPI (streaming `read`/`open`/`write` and the content-addressing `writeBlob` that digests a blob as it streams, plus `exists`/`size`/`list`/`delete` and `writeVersioned` for cross-node compare-and-set, over an object namespace), the `QuotaArtifactStore` decorator that caps a scope's stored content bytes, and the format-neutral content-addressed store (`Publication`) that every format publishes through - including its gated `publish(descriptor, stream)`, which stores the blob, runs the `ServiceLoader`-discovered `PublishInterceptor` chain over the neutral `ArtifactDescriptor`, and routes the pointer by the strongest disposition (accept / quarantine / reject). `java.base` only, so a format plugin builds on it without pulling in the server. |
| `build.jenesis.repository.store.filesystem` | `source/store/filesystem` | The default filesystem backend: blobs under a mounted root (`JENESIS_STORE_ROOT`), the provider `ArtifactStoreProvider.resolve` falls back to when no other backend is named. `provides` its `ArtifactStoreProvider`, discovered with `ServiceLoader`; the store SPI + `java.base` only. |
| `build.jenesis.repository.store.s3`       | `source/store/s3`         | S3-compatible backend (AWS SDK v2). Selected with `jenesis.repository.store=s3` and `JENESIS_AWS_BUCKET`; also GCS / MinIO via `JENESIS_AWS_ENDPOINT`. The version token is the object ETag, so `writeVersioned` is a true cross-node compare-and-set over S3's `If-None-Match` / `If-Match` conditional writes (no lock service). |
| `build.jenesis.repository.store.azure`    | `source/store/azure`      | Azure Blob backend (azure-storage-blob SDK). Selected with `jenesis.repository.store=azure-blob`; `JENESIS_AZURE_CONNECTION_STRING` (+ optional `JENESIS_AZURE_CONTAINER`). The version token is the blob ETag, so `writeVersioned` is a cross-node compare-and-set over Azure's `If-None-Match` / `If-Match` conditional writes. |
| `build.jenesis.repository.format`   | `source/format/spi`          | The `RepositoryFormat` SPI + the framework-neutral `FormatExchange`. A layout is a module that depends only on this and `provides RepositoryFormat`; the dispatcher discovers them with `ServiceLoader`, so formats plug in without the core knowing them. `java.base` + the store SPI only. |
| `build.jenesis.repository.format.java`     | `source/format/java`         | The shared Java repository-layout primitives the Maven and Jenesis layouts build on: reading a jar's module name and parsing a Maven request path (`JavaLayout`). It also carries the cross-publish bridge (`ModuleView`) - exported *only* to those two modules, so cross-publishing stays off the public SPI. |
| `build.jenesis.repository.format.maven`    | `source/format/maven`        | The Maven layout (`/repository/maven/...`): stores the blob, generates `maven-metadata.xml` on read, proxies Maven Central. When a modular jar is published, it cross-publishes the jar's module view into the Jenesis layout over the bridge (it `uses` the `ModuleView` the Jenesis layout provides) - the one required cross-publish, and it goes one way. |
| `build.jenesis.repository.format.jenesis`  | `source/format/jenesis`      | The Jenesis module layout (`/repository/module/...`, `/repository/artifact/...`): stores and serves modules over the same content-addressed blob. It `provides` the `ModuleView` the Maven layout uses to mirror a published modular jar in by module name; a module published here stays in the module layout (it is not mirrored back to Maven). |
| `build.jenesis.repository.format.oci`      | `source/format/oci`          | The OCI / Docker registry format (`/v2/` Distribution API), so `docker push` / `docker pull` work over the same store. Self-contained (SPI + store only): an OCI `sha256:` digest *is* the content-addressed `blobs/<hex>` key, so layers, configs and manifests dedupe with everything else. |
| `build.jenesis.repository.format.raw`      | `source/format/raw`          | The generic (raw) layout (`/repository/raw/...`): a plain content-addressed file store over the same `Publication` primitives - `PUT` stores a file, `GET` serves it back. It also `provides` a `RepositoryImporter`, so raw/generic assets migrate in alongside Maven and OCI. |
| `build.jenesis.repository.importer`   | `source/importer/spi`          | The import-source SPI - the read half of a migration. An `ImportSource` walks a foreign repository's assets; an `ImportSourceProvider` builds one for a named incumbent from an `ImportRequest`. A connector is a module that `provides` a provider, discovered with `ServiceLoader`, so the server supports another incumbent without knowing it. A connector reads and writes its own JSON with Jackson. |
| `build.jenesis.repository.importer.nexus`    | `source/importer/nexus`        | The Sonatype Nexus 3 connector: `provides` an `ImportSourceProvider` that pages the components REST API by continuation token (format reported per asset, so mixed repositories migrate in one pass). Import SPI + format SPI only. |
| `build.jenesis.repository.importer.artifactory` | `source/importer/artifactory` | The JFrog Artifactory connector: `provides` an `ImportSourceProvider` that reads the storage listing (a repository has one package type, supplied up front). Import SPI + format SPI only. |
| `build.jenesis.repository.server`   | `source/server`       | The dispatcher, format-neutral: it `uses RepositoryFormat`, loads every format via `ServiceLoader`, scopes the store, and enforces auth - with no knowledge of any layout, so it serves a fully capable repository even with no format on the module path (every request 404s until one is). The pull-through serve loop lives here (its HTTP fetcher is the discovered `source/proxy` module); the content-addressed store (`Publication`) sits in the store module, the Maven and Jenesis layouts and their cross-publishing are plugin modules, and the import connectors are discovered the same way - so the server names no layout and no incumbent. |
| `build.jenesis.repository.ui`       | `source/ui`           | A simple, extendable web console: browse artifacts, view repositories and their config. An open console shell with a panel-extension SPI, so additional panels plug in without a fork. |

Build & run
-----------

    java build/jenesis/Project.java build                       # build everything
    java build/jenesis/Project.java +source+store+s3 build          # one module (+ deps)

    # the repository on the filesystem backend
    JENESIS_STORE_ROOT=/var/lib/jenesis-repository \
      java -Djenesis.execute.module=source+server build/jenesis/Execute.java

The server `requires` no layout, backend or importer of its own (see *Extensible by design*):
it discovers whatever layout modules, store backends, importers and the `ui` console are on its
module path at startup, so a deployment selects the ones it wants alongside `source+server`.

The web console is served at `/console` - browse artifacts, view repositories and their
configuration. Sign-in is OAuth2 / OIDC; the `dev` profile (`SPRING_PROFILES_ACTIVE=dev`) swaps in a
built-in `admin`/`admin` form login for local runs.

A repository-wide storage cap is optional: `-Djenesis.repository.quota=10GB` (a byte count or a `K`/`M`/`G`/`T`
suffix) refuses a new artifact once stored content reaches the limit, with `507 Insufficient Storage`. Only
content blobs count; a deduped re-deploy of bytes already stored needs no new space.

A request rate ceiling is optional too: `-Djenesis.repository.rate-limit=600` (permits per minute) sheds excess
load with `429 Too Many Requests` and a `Retry-After`. It is metered per tenant (the key's tenant, or a shared
anonymous bucket), and the Actuator probes are never throttled.

A Jenesis build points at it with the existing knobs - no new client:

    -Djenesis.module.uri=https://repo.example.com/repository/ -Djenesis.module.token=jenk_<tenant>.<secret>
    -Djenesis.maven.uri=https://repo.example.com/repository/maven/

Importing from another repository
---------------------------------

To migrate off an incumbent manager, two SPIs meet in the content-addressed store. The read half is
an `ImportSource`, built by an `ImportSourceProvider` that a connector module ships and the server
discovers with `ServiceLoader`: the built-in ones page the Nexus components REST API by continuation
token and read the Artifactory storage listing, and another incumbent is one more module - the server
names none of them. The write half is a `RepositoryImporter` per format (in
`build.jenesis.repository.format`, discovered the same way). `RepositoryImport` walks a source and
routes each asset to the importer that handles its format, writing it through the format's own publish
path so the imported repository regenerates its own `maven-metadata.xml` and indexes rather than
copying the source's. The core imports **Maven** (with the module-layout bridge), **OCI / Docker** and
**raw / generic**; an asset whose format has no importer on the path is reported skipped and, because
content is read lazily, never downloaded.

The Artifactory source reads its listing with the deep File List API (`GET /api/storage/<repo>?list&deep=1`),
which JFrog gates behind Artifactory Pro - a self-hosted OSS instance answers `400` ("available only in
Artifactory Pro"). When it does, the connector falls back **seamlessly** to the OSS-available per-item Folder
Info API (`GET /api/storage/<repo>/<path>`, its `children` one level deep), recursed for the same file set -
N requests instead of one, checkpointing after each top-level subtree so an interrupted crawl resumes from
where it stopped (coarser than the paged Nexus walk, but the imports are content-addressed and idempotent, so
a re-run is safe regardless). So the same `artifactory` migration works unchanged against both a Pro and a
free Artifactory (the fallback is tested against a real `artifactory-oss` instance), and only a non-Pro error
surfaces.

    RepositoryImport.Result result = new RepositoryImport().run(
            new NexusSource(URI.create("https://nexus.example.com"), "maven-releases", PullThroughCache.http()),
            store);

A migration is also launched on a running server and runs in the background: `POST /repository/admin/import`
(a `repository:write` operation) starts a job and returns its id; `GET /repository/admin/import/<id>` reports its
state and running counts. The job persists a resume cursor, so a `resume` naming a prior job continues the
walk from where it stopped:

    curl -X POST http://repo.example.com/repository/admin/import -d \
      '{"source":"nexus","url":"https://nexus.example.com","repository":"maven-releases"}'
    # {"job":"a1b2...","state":"running"}
    curl http://repo.example.com/repository/admin/import/a1b2...
    # {"state":"completed","imported":128,"skipped":0,"skippedFormats":[],"cursor":null}
