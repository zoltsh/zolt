# Usage

Common Zolt workflows. See [FEATURES.md](./FEATURES.md) for the complete feature
set and [REFERENCE.md](./REFERENCE.md) for configuration and contracts.

## Start

```sh
zolt init hello --group com.example --java 21
cd hello
zolt add com.google.guava:guava:33.4.0-jre
zolt add test org.junit.platform:junit-platform-console-standalone:1.11.4
zolt test
zolt package
```

Zolt writes `zolt.toml`, resolves `zolt.lock`, compiles the project, runs tests,
and packages the artifact.

## Build

```sh
zolt build
zolt run -- ARGS
zolt package
zolt run-package -- ARGS
zolt clean
```

Use `zolt tasks` and `zolt aliases` to inspect project-defined commands. Run a
task with `zolt task NAME -- ARGS`.

## Dependencies

```sh
zolt add GROUP:ARTIFACT:VERSION
zolt add test GROUP:ARTIFACT:VERSION
zolt add runtime GROUP:ARTIFACT:VERSION
zolt add provided GROUP:ARTIFACT:VERSION
zolt add processor GROUP:ARTIFACT:VERSION
zolt remove GROUP:ARTIFACT
zolt platform add GROUP:ARTIFACT:VERSION
zolt resolve --locked
zolt resolve --offline
```

Inspect and update the graph:

```sh
zolt tree
zolt why GROUP:ARTIFACT
zolt conflicts
zolt policy
zolt outdated
zolt update --dry-run
```

Zolt supports BOM imports, version aliases, scopes, exclusions, optional
dependencies, classifiers, artifact types, and constraints.

See [Resolution](./REFERENCE.md#resolution-and-lockfile-contracts) and
[Updates](./REFERENCE.md#dependency-updates).

## Workspaces

```sh
zolt resolve --workspace
zolt build --workspace --all
zolt test --workspace --member apps/api
zolt package --workspace --members apps/api,tools
zolt check --workspace --context ci --all
zolt clean --workspace --all
```

Workspace commands select members consistently and run them in dependency order.

## Test

```sh
zolt test --test com.example.MainTest
zolt test --tests '*IntegrationTest'
zolt test --include-tag fast --exclude-tag slow
zolt test --suite smoke
zolt test --shard 1/4
zolt integration-test
zolt coverage
zolt test plan --shard-count 4 --format json
```

Write JUnit XML with `--reports-dir`, profile tests with `--profile-tests`, and
set coverage floors in `zolt.toml`. See [Tests](./REFERENCE.md#tests-and-coverage).

## Toolchains

```sh
zolt toolchain install java 21 --graalvm --native-image
zolt toolchain sync
zolt toolchain status
zolt toolchain list
zolt exec -- java -version
zolt shims install
```

Projects can pin managed Temurin or GraalVM JDKs. A separate test-runtime JDK can
verify the Java version users actually run.

See [Toolchains](./REFERENCE.md#java-toolchains).

## Frameworks

Zolt includes packaging or generated-source paths for Spring Boot, Quarkus,
OpenAPI, and Protobuf, with documented Micronaut and Vert.x examples.

```sh
zolt package --mode spring-boot
zolt package --mode spring-boot-war
zolt package --mode quarkus
zolt quarkus plan
zolt quarkus test-plan
```

See [Frameworks](./REFERENCE.md#frameworks-and-generated-sources) and the
[examples](./examples/).

## Package

```sh
zolt package --plan
zolt package --mode thin
zolt package --mode uber
zolt package --mode war
zolt package --mode spring-boot
zolt package --mode quarkus
zolt native
```

Packages support sources, Javadoc, manifests, Maven metadata, and evidence.
Native builds use the resolved GraalVM toolchain.

## Publish

```sh
zolt publish --dry-run
zolt publish --dry-run --central
zolt publish --central --wait
zolt publish --workspace --dry-run
zolt publish --workspace --central --wait
```

Zolt publishes to Maven-compatible repositories and the Sonatype Central Portal.
It supports checksums, GPG signatures, sources, Javadoc, SBOMs, workspace
families, full preflight, and safe resume. See
[Publishing](./REFERENCE.md#publishing-to-maven-repositories).

## Inspect

```sh
zolt doctor
zolt plan --target package
zolt plan --target test --format json
zolt classpath audit --format json
zolt ide model --format json
zolt licenses
zolt sbom
```

Human-readable output is the default. Commands that feed tools expose stable
JSON.

## CI

```sh
zolt check --context local
zolt check --context ci --reports-dir target/test-reports
zolt check --context ci --require-package
zolt check --context ci --require-publish-dry-run
zolt check --context ci --require-offline-ready
zolt --color never --progress never check --format json
zolt --timings --timings-format json package
```

`zolt check` validates project-owned evidence instead of treating CI as a
separate build model.

## Network

Zolt supports HTTP and HTTPS proxies, authenticated proxies, private certificate
authorities, repository credentials, Java mirrors, and isolated user state
through `ZOLT_USER_HOME`. See [Network](./REFERENCE.md#enterprise-networks).

## Cache

```sh
zolt cache status
zolt cache prune
zolt build --no-build-cache
```

The build cache is content-addressed, local by default, and optionally remote.
See [Cache](./REFERENCE.md#build-cache).

## Migration

```sh
zolt explain --source auto
zolt explain --scorecard
zolt explain --blockers
zolt explain --emit-toml
zolt explain verify --format json
```

Migration commands audit Maven or Gradle inputs, identify blockers, draft
configuration, and compare the incumbent build with Zolt.

## Self

```sh
zolt self releases
zolt self install VERSION
zolt self versions
zolt self use VERSION
zolt self prune --keep 3 --dry-run
zolt self rollback
zolt self update
```

Installer-managed versions support channels, switching, pruning, updates, and
rollback. Zolt also builds and verifies itself with `self-check`, `self-parity`,
and `native-smoke`.

## More

- [Features](./FEATURES.md)
- [Reference](./REFERENCE.md)
- [Examples](./examples/)
- [Benchmarks](./docs/benchmarks/)
- [Breaking changes](./docs/breaking-changes.md)
