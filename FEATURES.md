# Zolt Features

Zolt covers the Java project lifecycle from project creation through release.
See [USAGE.md](./USAGE.md) for commands, configuration, and detailed behavior.

## CLI

- One native binary with fast startup.
- Commands grouped by job in `zolt --help` and `zolt --list`.
- Human-readable output by default and JSON output for automation.
- Actionable failures that identify the problem, relevant file, and next step.
- Typed build, test, package, native, and CI plans before execution.
- Non-mutating previews for dependency updates, package contents, publishing,
  and cache pruning.
- Consistent workspace selection with `--workspace`, `--all`, `--member`, and `--members`.
- Consistent automation controls with `--quiet`, `--verbose`, `--color`, `--progress`, and `--timings`.
- Run from another project directory with `--directory`.
- Project-defined command aliases and tasks.

## Projects

- Create a Java project or workspace with `zolt init`.
- Keep the project model in `zolt.toml`.
- Build, test, package, run, check, clean, and publish selected workspace members.
- Schedule workspace members in dependency order.
- Use workspace dependencies without publishing intermediate artifacts.
- Configure project tasks, aliases, sources, resources, generated sources, and integration-test roots.

## Dependencies

- Add and remove compile, runtime, provided, development, test, and annotation-processor dependencies.
- Import Maven BOMs and manage shared version aliases.
- Configure exclusions, optional dependencies, classifiers, artifact types, and strict constraints.
- Resolve to a deterministic `zolt.lock`.
- Pin POM and artifact integrity with SHA-256 hashes.
- Verify locked and offline builds.
- Inspect the graph with `tree`, `why`, `conflicts`, `policy`, and `classpath audit`.
- Report available versions with `outdated`.
- Preview and apply dependency updates with `update`.
- Use authenticated Maven-compatible repositories and local repository overlays.

## Builds

- Compile Java sources and copy or filter resources.
- Run applications directly from the project model.
- Package and run the produced artifact.
- Inspect typed build and package plans without executing them.
- Reuse local build outputs through a content-addressed cache.
- Share build outputs through an optional remote cache.
- Keep cache inputs tied to sources, dependencies, configuration, and the resolved JDK.

## Tests

- Run JUnit Platform and JUnit Vintage tests.
- Run Groovy and Spock tests.
- Configure separate unit and integration-test roots.
- Select test classes, methods, patterns, suites, and JUnit tags.
- Split tests into deterministic shards.
- Balance supported test workers from profiling evidence.
- Write JUnit XML reports and test profile JSON.
- Generate JaCoCo HTML, XML, and execution-data reports.
- Enforce line, branch, instruction, and method coverage floors.
- Compile with one JDK and run tests on a separately pinned target JDK.

## Toolchains

- Manage Eclipse Temurin and GraalVM Community JDKs.
- Pin project JDKs in `zolt.toml` and `zolt.lock`.
- Install and select a user-global default JDK.
- Use `prefer-managed`, `require-managed`, or `allow-system` policy.
- Run commands inside the resolved JDK with `zolt exec`.
- Install opt-in `java`, `javac`, `jar`, `javadoc`, `jshell`, and `native-image` shims.
- Build native executables with GraalVM Native Image.

## Frameworks

- Package Spring Boot applications and WAR files.
- Package Quarkus fast-jar applications and inspect augmentation plans.
- Build Micronaut and Vert.x application shapes through documented examples.
- Generate Java sources from OpenAPI and Protobuf definitions.
- Run declared process or JVM generation steps with typed inputs, outputs, ordering, and cache policy.
- Export generated roots through the IDE model.

## Packaging

- Create thin jars with a verified runtime-classpath sidecar.
- Create deterministic uber jars with explicit duplicate handling.
- Create plain WAR and Spring Boot WAR packages.
- Create Spring Boot and Quarkus packages.
- Publish BOM-only workspace members.
- Attach sources and Javadoc jars.
- Configure manifest entries and Maven package metadata.
- Inspect package contents before writing an archive.

## Publishing

- Publish artifacts and POMs to Maven-compatible repositories.
- Check Maven Central readiness without uploading.
- Publish through the Sonatype Central Portal.
- Attach checksums, detached GPG signatures, sources, Javadoc, and CycloneDX SBOMs.
- Publish a workspace family in dependency order or as one Central bundle.
- Preflight the complete upload set before the first request.
- Resume interrupted plain-repository publishes safely.
- Build native release archives and verify them by unpacking and running real workflows.

## Quality

- Run local and CI quality contexts with `zolt check`.
- Require test reports, coverage, package evidence, publish readiness, and offline readiness.
- Generate CycloneDX SBOMs from the locked graph.
- Report dependency licenses without network access.
- Enforce dependency and license policy.
- Emit stable JSON for CI and other tooling.
- Control progress, color, summaries, diagnostics, and timings independently.

## Insight

- Export single-project and workspace IDE models as JSON.
- Check lockfile freshness without rewriting it.
- Audit Maven or Gradle projects before migration.
- Show migration scorecards and focused blockers.
- Generate a draft `zolt.toml` from supported Maven and Gradle inputs.
- Compare an incumbent build with its Zolt migration.
- Inspect dependency selection, classpaths, package plans, test plans, and framework inputs.

## Enterprise

- Use HTTP and HTTPS proxies, including authenticated proxies.
- Add private certificate authorities without replacing the JDK trust store.
- Authenticate repositories with basic credentials or bearer tokens referenced through environment variables.
- Redirect all user-global state with `ZOLT_USER_HOME`.
- Configure Java toolchain mirrors.
- Keep secrets out of project configuration, lockfiles, fingerprints, plans, and logs.

## Distribution

- Install and update the native Zolt binary through release channels.
- List, install, select, execute, prune, and roll back installed versions.
- Build Zolt with Zolt.
- Compare bootstrap and self-hosted artifacts.
- Smoke native Zolt binaries against real project and release workflows.

## Platforms

Native Zolt releases support:

- Linux x64
- Linux arm64
- macOS arm64
- macOS x64

Windows x64 is experimental and is not yet a supported host.

## Boundaries

Zolt is Java-focused and intentionally opinionated. Dynamic versions, version
ranges, remote SNAPSHOT dependencies, and arbitrary build-plugin behavior are
outside its reproducible model. Use `zolt explain`, `zolt plan`, and the
[examples](./examples/) to check a project's fit.

## More

- [Usage guide](./USAGE.md)
- [Examples](./examples/)
- [Benchmarks](./docs/benchmarks/)
- [Security](./SECURITY.md)
