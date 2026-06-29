jenesis-repository
==================

A dual-layout artifact repository: it serves the same artifacts under both the
**Maven layout** (so any Maven, Gradle, or Jenesis Maven-mode build resolves them)
and the **Jenesis module layout** (so a `modular`/`modular_to_maven` build resolves
them by module name). Publish a module once and the server **computes its POM and
`maven-metadata.xml`** so both ecosystems can consume it from a single upload.

It is **free and open**, and **extensible by design**: storage backends, repository
formats, importers and console panels all plug in through `ServiceLoader` SPIs without
forking the core. It may be accompanied by a commercial offering in the future that
builds on these same extension points.

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

Modules
-------

    provider/                 storage backends (not a module itself)
      filesystem/             the ArtifactStore SPI + the default filesystem backend
      s3/                     S3-compatible backend (AWS S3, GCS, MinIO)
      azure/                  Azure Blob backend
    server/                   the apps (not a module itself)
      repository/             the dual-layout repository server (RepositoryServer)
      ui/                     a simple, extendable web console (browse, search, repo config)

The console is an open shell with a **panel-registration SPI**, so additional panels
plug in through the console's extension points without forking the core.

| Module | Folder | What it is |
|--------|--------|------------|
| `build.jenesis.repository.store`    | `provider/filesystem` | The `ArtifactStore` SPI (`get`/`put`/`putIf`/`list`/`delete` over an object namespace) + the filesystem backend. `java.base` only. |
| `build.jenesis.repository.s3`       | `provider/s3`         | S3-compatible backend (AWS SDK v2). `JENESIS_STORE=s3`; also GCS / MinIO via `JENESIS_AWS_ENDPOINT`. The version token is the object ETag, so `writeVersioned` is a true cross-node compare-and-set over S3's `If-None-Match` / `If-Match` conditional writes (no lock service). |
| `build.jenesis.repository.azure`    | `provider/azure`      | Azure Blob backend (azure-storage-blob SDK). `JENESIS_STORE=azure-blob`; `JENESIS_AZURE_CONNECTION_STRING` (+ optional `JENESIS_AZURE_CONTAINER`). The version token is the blob ETag, so `writeVersioned` is a cross-node compare-and-set over Azure's `If-None-Match` / `If-Match` conditional writes. |
| `build.jenesis.repository.format`   | `format/spi`          | The `RepositoryFormat` SPI + the framework-neutral `FormatExchange`. A layout is a module that depends only on this and `provides RepositoryFormat`; the dispatcher discovers them with `ServiceLoader`, so formats plug in without the core knowing them. `java.base` + the store SPI only. |
| `build.jenesis.repository.maven`    | `format/maven`        | The Maven / Jenesis-module format (`/maven/...`, `/module/...`, `/artifact/...`): the dual layout, cross-publishing, POM generation, and `maven-metadata.xml` generated on read. Builds on the core's `Publication` / `MavenMetadata`. |
| `build.jenesis.repository.oci`      | `format/oci`          | The OCI / Docker registry format (`/v2/` Distribution API), so `docker push` / `docker pull` work over the same store. Self-contained (SPI + store only): an OCI `sha256:` digest *is* the content-addressed `blobs/<hex>` key, so layers, configs and manifests dedupe with everything else. |
| `build.jenesis.repository`          | `server/repository`   | The dispatcher + shared Maven-layout primitives: it `uses RepositoryFormat`, loads every format via `ServiceLoader`, scopes the store, and enforces auth; `Publication`, `MavenMetadata`, the module bridge and POM generator live here for the Maven format to build on. Optional pull-through proxy and basic age/size retention. |
| `build.jenesis.repository.ui`       | `server/ui`           | A simple, extendable web console: browse and search artifacts, view repositories and their config. An open console shell with a panel-extension SPI, so additional panels plug in without a fork. |

Build & run
-----------

    java build/jenesis/Project.java build                       # build everything
    java build/jenesis/Project.java +provider+s3 build          # one module (+ deps)

    # the repository on the filesystem backend
    JENESIS_STORE_ROOT=/var/lib/jenesis-repository \
      java -Djenesis.execute.module=server+repository build/jenesis/Execute.java

A Jenesis build points at it with the existing knobs - no new client:

    -Djenesis.module.uri=https://repo.example.com/ -Djenesis.module.token=<tenant>.<secret>
    -Djenesis.maven.uri=https://repo.example.com/maven/

Importing from another repository
---------------------------------

To migrate off an incumbent manager, a `RepositoryImporter` (the SPI lives in
`build.jenesis.repository.format`, discovered by `ServiceLoader` like a format) imports an
ecosystem's artifacts into the content-addressed store. A source connector walks the incumbent's
admin API - `NexusSource` pages the Nexus components REST API by continuation token,
`ArtifactorySource` reads the Artifactory storage listing - and `RepositoryImport` routes each asset
to the importer that handles its format, writing it through the format's own publish path so the
imported repository regenerates its own `maven-metadata.xml` and indexes rather than copying the
source's. The core imports **Maven** (with the module-layout bridge), **OCI / Docker** and
**raw / generic**; an asset whose format has no importer on the path is reported skipped and, because
content is read lazily, never downloaded - additional format importers plug in through the same SPI.

    RepositoryImport.Result result = new RepositoryImport()
            .run(new NexusSource(URI.create("https://nexus.example.com"), "maven-releases"), store);

A migration is also launched on a running server and runs in the background: `POST /admin/import`
(a `repository:write` operation) starts a job and returns its id; `GET /admin/import/<id>` reports its
state and running counts. The job persists a resume cursor, so a `resume` naming a prior job continues the
walk from where it stopped:

    curl -X POST http://repo.example.com/admin/import -d \
      '{"source":"nexus","url":"https://nexus.example.com","repository":"maven-releases"}'
    # {"job":"a1b2...","state":"running"}
    curl http://repo.example.com/admin/import/a1b2...
    # {"state":"completed","imported":128,"skipped":0,"skippedFormats":[],"cursor":null}
