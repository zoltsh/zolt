# Features

Zolt 1.0 focuses on a dependable Java workflow: resolve, build, test, package,
and produce native executables. See [Usage](./USAGE.md) for commands and
[Compatibility](./docs/compatibility.md) for the release contract.

## Build

```sh
zolt init hello --group com.example --java 21
cd hello
zolt build
zolt run
```

- **Projects.** Sources, resources, annotation processors, generated Java,
  project tasks, and aliases in `zolt.toml`.
- **Dependencies.** Maven repositories, BOMs, version aliases, API and
  implementation boundaries, runtime/test/processor scopes, exclusions,
  classifiers, constraints, and checksum-verified `zolt.lock` files.
- **Rebuilds.** Changed Java inputs recompile the affected source set into clean
  output. Unchanged verified inputs and content-addressed caches can reuse work.
- **Workspaces.** Build selected members and their dependencies in order with a
  shared root lockfile.
- **Toolchains.** Pin Temurin or GraalVM Community JDKs, including a separate
  test-runtime JDK. Resolve once and build locked or offline.

## Test

```sh
zolt test
zolt integration-test
zolt coverage
```

- **Engines.** JUnit Platform, Jupiter, Vintage, and Spock/Groovy tests.
- **Selection.** Classes, methods, patterns, suites, tags, and deterministic shards.
- **Reports.** JUnit XML, test profiles, and JaCoCo HTML/XML with coverage floors.
- **Workspaces.** Compile and run tests against each member's dependency graph.

## Package and native image

```sh
zolt package
zolt run-package
zolt native
```

- **Packages.** Jars, deterministic uber-jars, WARs, Spring Boot JVM jars/WARs,
  BOMs, sources, and Javadoc.
- **Native executables.** GraalVM Native Image builds from the locked Java
  runtime closure. Native input packages have their own output directory.
  Failed native builds preserve the previous executable.
- **Publishing.** Maven-compatible repositories and Sonatype Central, with
  preflight, checksums, GPG signatures, SBOMs, workspace publication, and resume.
- **Generated Java.** Locked OpenAPI and Protobuf tools plus declared process
  or JVM generation steps.

## Inspect and automate

- **Dependency insight.** Graphs, inclusion paths, conflicts, classpath audits,
  dependency updates, and license reports.
- **CI.** Plans, quality checks, package/publish evidence, coverage, and
  offline-readiness checks.
- **Tools.** IDE model export, Maven/Gradle migration reports, and versioned JSON
  for the contracts listed in [Compatibility](./docs/compatibility.md).
- **Distribution.** Native CLI releases for Linux and macOS on x64 and arm64,
  with installer-managed updates, version selection, and rollback.

## Experimental features

These features are disabled unless the command runs with `ZOLT_EXPERIMENTAL=1`:

- Kotlin/JVM main and tests, compiler plugins, KAPT, and KSP.
- Groovy main compilation, including joint Java/Groovy source sets.
- Quarkus augmentation, packaging, and framework commands.
- Spring Boot AOT/native images; the current canary baseline is Spring Boot 3.3
  on Java 21.

```sh
ZOLT_EXPERIMENTAL=1 zolt init hello-kotlin --language kotlin
ZOLT_EXPERIMENTAL=1 zolt build --cwd hello-kotlin
```

Experimental features can change independently of the stable Java contract.
Their presence in examples or the [Reference](./REFERENCE.md) does not make them
part of the supported 1.0 scope. See the
[Kotlin/JVM support matrix](./docs/kotlin-jvm-support.md) for its current boundary.
Windows hosting remains experimental; no Windows native binary is published.

Dynamic versions, version ranges, remote SNAPSHOT dependencies, and arbitrary
Maven/Gradle plugin execution are outside Zolt's model.

## More

- [Usage](./USAGE.md)
- [Reference](./REFERENCE.md)
- [Examples](./examples/)
- [Benchmarks](./docs/benchmarks/)
- [Security](./SECURITY.md)
