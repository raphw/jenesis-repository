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
Maven layout, the module layout, OCI/Docker - and every storage backend, importer and
console panel is a `ServiceLoader` plug-in over one content-addressed store, with no
core to fork (see *Extensible by design*, below). It may be accompanied by a commercial
offering in the future that builds on these same extension points.

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
retention and console as a Maven artifact, for free.

Extensible by design
--------------------

Everything the server speaks is a plug-in over one content-addressed store, discovered
with `ServiceLoader`. There is no central table of formats to edit and no core to fork;
a deployment simply runs whichever plug-ins are on its module path:

 - **Repository formats** (`RepositoryFormat`) - the wire protocol of one client
   ecosystem. Maven and OCI/Docker ship in the core; another ecosystem (npm, PyPI,
   NuGet, ...) is one more module.
 - **Storage backends** (`ArtifactStoreProvider`, returning an `ArtifactStore`) -
   filesystem, S3 / GCS / MinIO and Azure Blob ship in the core; a new backend is a
   single provider.
 - **Importers** (`RepositoryImporter`) - migrate one ecosystem off an incumbent
   manager (see *Importing from another repository*, below).
 - **Console panels** (`Panel`) - contribute a page to the web console.
 - **Pull-through proxying** (`ProxyFormat`) - an opt-in capability a format adds to
   mirror an upstream; the OCI format uses it to mirror Docker Hub.

A whole format is three methods over the already tenant-and-repository-scoped store:

    public interface RepositoryFormat {
        String name();                                              // "maven", "oci", "npm"
        boolean handles(String path);                               // does it own this request path?
        void handle(FormatExchange exchange, ArtifactStore store);  // serve or accept the request
    }

So a new ecosystem is a module that depends only on this SPI and the store, declares
`provides RepositoryFormat with ...`, and is discovered at startup - it inherits the
content-addressed storage, multi-tenancy, authorization, retention and console without
touching any of them. The OCI/Docker registry is itself the proof: a single
self-contained module that needs nothing but the SPI and the store.

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
    import java.io.ByteArrayInputStream;
    import java.io.ByteArrayOutputStream;
    import java.io.IOException;

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
                case "PUT" -> {
                    store.write(key, new ByteArrayInputStream(exchange.requestBytes()));
                    exchange.respond(201);
                }
                case "GET" -> {
                    if (!store.exists(key)) {
                        exchange.respond(404);
                    } else {
                        ByteArrayOutputStream body = new ByteArrayOutputStream();
                        store.read(key, body);
                        exchange.respond(200, body.toByteArray());
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
module and select it alongside `server+repository`). On the next start the dispatcher
loads it, routes every `/files/...` request to it, and it inherits multi-tenancy,
authorization, retention and the console untouched.

Every extension point works the same way - implement the interface, then `provides ...
with ...` it from a module on the path:

| Plug-in | Implement | `provides` service |
|---------|-----------|--------------------|
| A repository format / file handler | `RepositoryFormat`      | `build.jenesis.repository.format.RepositoryFormat`   |
| A storage backend                  | `ArtifactStoreProvider` | `build.jenesis.repository.store.ArtifactStoreProvider` |
| A migration importer               | `RepositoryImporter`    | `build.jenesis.repository.format.RepositoryImporter` |
| A console panel                    | `Panel`                 | `build.jenesis.repository.ui.Panel`                  |

A format may additionally implement `ProxyFormat` to gain pull-through mirroring; the
OCI format does exactly that to mirror Docker Hub.

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
| `build.jenesis.repository.store`    | `provider/filesystem` | The `ArtifactStore` SPI (`read`/`write`/`exists`/`list`/`delete`, plus `writeVersioned` for cross-node compare-and-set, over an object namespace) + the filesystem backend. `java.base` only. |
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
