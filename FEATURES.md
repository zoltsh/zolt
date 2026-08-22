# Features

Zolt covers Java projects from setup through release. See
[USAGE.md](./USAGE.md) for details.

## Highlights

- **Native CLI.** Install Zolt and start working.
- **Small project model.** `zolt.toml` defines the project. `zolt.lock` defines
  the resolved graph.
- **Reproducible dependencies.** Resolution, repository selection, and artifact
  integrity are locked and verifiable offline.
- **Managed Java.** Zolt installs and pins Temurin and GraalVM toolchains,
  including separate build and test-runtime JDKs.
- **Workspaces.** Build, test, package, and publish selected members in
  dependency order.
- **Full testing.** JUnit, Spock, integration tests, suites, tags, shards,
  profiling, reports, and coverage.
- **Shipping.** Framework packages, native images, Maven publishing, signing,
  SBOMs, and release verification.
- **Built-in insight.** Plans, dependency explanations, quality checks, IDE
  models, migration audits, and JSON output.

## Develop

```sh
zolt init hello
zolt add com.google.guava:guava:33.4.0-jre
zolt build
zolt run
```

- **Projects.** Configure sources, resources, generated sources, tasks, aliases,
  and integration-test roots.
- **Dependencies.** Use API, implementation, runtime, provided, development,
  test, and annotation-processor lanes under one `[dependencies]` namespace.
- **Metadata.** Import BOMs and configure version aliases, exclusions,
  classifiers, artifact types, optional dependencies, and strict constraints.
- **Resolution.** Write a deterministic lockfile with SHA-256 hashes. Build
  locked or fully offline.
- **Updates.** Report, preview, and apply dependency updates.
- **Workspaces.** Select members individually or build the complete workspace in
  dependency order.
- **Cache.** Reuse content-addressed local outputs or share them through an
  optional remote cache.

## Test

```sh
zolt test
zolt test --suite smoke --shard 1/4
zolt integration-test
zolt coverage
```

- **Support.** JUnit Platform, JUnit Vintage, Groovy test sources, and Spock.
- **Selection.** Classes, methods, patterns, suites, and JUnit tags.
- **Scale.** Deterministic shards and profiling-based worker balancing.
- **Reports.** JUnit XML, test profile JSON, and JaCoCo HTML, XML, and
  execution data.
- **Coverage.** Line, branch, instruction, and method floors.
- **Runtime.** Compile with one JDK and run tests on a separately pinned target
  JDK.

## Frameworks

- **Spring Boot.** Application jars, executable packaging, and WAR packaging.
- **Quarkus.** Fast-jar packaging plus augmentation and test-plan inspection.
- **Micronaut.** Documented HTTP application and annotation-processor examples.
- **Vert.x.** Documented HTTP and PostgreSQL application examples.
- **OpenAPI.** Locked tooling and generated Java sources.
- **Protobuf.** Protobuf and gRPC generated Java sources.
- **Exec.** Declared process or JVM generation steps with typed inputs, outputs,
  ordering, and cache policy.

## Ship

```sh
zolt package --plan
zolt package
zolt publish --dry-run --central
zolt native
```

- **Packages.** Jars, deterministic uber jars, WARs, Spring Boot, Quarkus,
  BOMs, sources, and Javadoc.
- **Metadata.** Manifest entries, Maven coordinates, project metadata,
  checksums, and package evidence.
- **Publishing.** Maven-compatible repositories and the Sonatype Central Portal.
- **Signing.** GPG signatures, checksums, sources, Javadoc, and CycloneDX SBOMs.
- **Families.** Preflight and publish complete workspaces in dependency order or
  as one Central bundle.
- **Recovery.** Resume interrupted plain-repository publishes without repeating
  completed uploads.
- **Native.** Build GraalVM native images, assemble release archives, and verify
  them through real workflows.

## Inspect

```sh
zolt tree
zolt why GROUP:ARTIFACT
zolt plan --target package
zolt check --context ci
```

- **Dependencies.** Inspect the graph, inclusion paths, conflicts, policy, and
  classpaths.
- **Plans.** Preview build, test, package, native, and CI work without executing
  it.
- **Quality.** Require test reports, coverage, package evidence, publish
  readiness, and offline readiness.
- **Supply chain.** Generate CycloneDX SBOMs and offline license reports from the
  locked graph.
- **IDE.** Export single-project and workspace models as JSON.
- **Migration.** Audit Maven and Gradle projects, show blockers and scorecards,
  generate draft configuration, and compare builds.

## Operate

```sh
zolt toolchain sync
zolt toolchain sync --refresh
zolt toolchain status
zolt self update
zolt config show --effective
```

- **Toolchains.** Resolve concrete Java feature releases from Temurin or GraalVM
  Community, pin exact GA artifacts, and refresh patches explicitly. Run
  commands inside the resolved JDK or install opt-in Java shims.
- **Enterprise.** Use authenticated proxies, private CAs, repository
  credentials, Java mirrors, and isolated user state.
- **Distribution.** Install, select, execute, prune, update, and roll back native
  Zolt versions.
- **Automation.** Control summaries, diagnostics, progress, color, timings, and
  stable JSON independently.
- **Self-hosting.** Build Zolt with Zolt and compare bootstrap and self-hosted
  artifacts.

## Platforms

Native releases are published for Linux and macOS on x64 and arm64. Windows
host support is experimental; no Windows native binary is published yet.

## Boundaries

Zolt is Java-focused and reproducible by design. Dynamic versions, version
ranges, remote SNAPSHOT dependencies, and arbitrary build-plugin behavior are
outside that model. Use `zolt explain`, `zolt plan`, and the
[examples](./examples/) to check a project's fit.

## More

- [FAQ](./FAQ.md)
- [Usage](./USAGE.md)
- [Reference](./REFERENCE.md)
- [Examples](./examples/)
- [Benchmarks](./docs/benchmarks/)
- [Security](./SECURITY.md)
