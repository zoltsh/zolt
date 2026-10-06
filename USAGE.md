# Usage

Common Zolt workflows. See [FEATURES.md](./FEATURES.md) for the complete feature
set and [REFERENCE.md](./REFERENCE.md) for configuration and contracts.

## Start

```sh
zolt init hello --group com.example --java 21
cd hello
zolt test
zolt package
```

`init` includes JUnit and a passing test. Use `--no-tests` for a bare project.
Pass `--language kotlin` to generate a Kotlin/JVM application with an aligned
compiler, standard library, and explicit Kotlin main root. The default template
also includes a Kotlin test root and passing JUnit test; `--no-tests` omits them.
Java is the default language.

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
zolt add GROUP:ARTIFACT:VERSION --scope test
zolt remove GROUP:ARTIFACT --scope test
zolt versions set ALIAS VERSION
zolt platforms set GROUP:ARTIFACT VERSION
zolt resolve --locked
zolt resolve --offline
```

Dependency scope is an explicit option, not a positional prefix. Scope values
are `implementation` (default), `api`, `runtime`, `provided`, `dev`, `test`,
`processor`, and `test-processor`. Every mutation refreshes `zolt.lock` unless
`--no-resolve` is given, which commits only the source-safe manifest edit and
names the resolve command that refreshes the stale lock.

In a Kotlin/JVM source set, `processor` and `test-processor` dependencies run
through a version-aligned, isolated KAPT toolchain. Zolt makes generated Java
types visible to Kotlin and Java consumers in that source set. A Kotlin main or
test source set may instead run locked KSP2 processors through a required
`[generated.main]` or `[generated.test]` step:

```toml
[generated.tools.ksp]
version = "2.2.0-2.0.2"
coordinates = [
    { coordinate = "com.example:my-ksp-processor", version = "1.0.0" },
]

[generated.main.symbols]
kind = "ksp"
options = { "processor.mode" = "production" }
```

Use `[generated.test.symbols]` for the matching unit- and integration-test
source-set projection.

The KSP release must match the configured Kotlin compiler. Zolt locks the KSP
engine and processor closures separately, owns the generated Kotlin, Java, and
resource lanes, and includes their identity and bytes in reuse decisions. KSP
test generation uses the member's main output plus its ordered test compile
classpath.

The bounded Kotlin preview also supports official compiler plugins through
closed selectors:

```toml
[toolchain.kotlin]
version = "2.2.0"
plugins = ["serialization", "spring"]

[dependencies]
"org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
"org.jetbrains.kotlinx:kotlinx-serialization-core-jvm" = "1.9.0"
```

Zolt locks each selected compiler plugin at the Kotlin compiler version in the
isolated tool closure. `serialization` generates serializers; its runtime
remains an ordinary application dependency and must be declared explicitly.
`spring` invokes Kotlin's all-open Spring preset so recognized Spring-annotated
types can be proxied without manually writing `open`; it does not add Spring
libraries. Select either plugin or both. Arbitrary compiler-plugin coordinates
and plugin options are outside this preview.

Pass processor options as scoped compiler arguments. Each option requires the
matching processor dependency lane:

```toml
[compiler]
args = ["-Amapstruct.defaultComponentModel=jakarta"]

[compiler.test]
args = ["-Azolt.fixture.mode=test"]
```

Inspect and update the graph:

```sh
zolt tree
zolt tree --format json
zolt why GROUP:ARTIFACT
zolt conflicts
zolt policy
zolt outdated
zolt update --dry-run
zolt outdated --format json --schema-version 2
zolt update --target-id TARGET_ID --to VERSION --format json --schema-version 2
```

Zolt supports BOM imports, version aliases, scopes, exclusions, optional
dependencies, classifiers, artifact types, and constraints.

Automation should discover opaque target IDs from schema-v2 `outdated` output,
then pass one ID and an exact newer fixed version to `update`. Exact mode works
from a standalone project, workspace root, or member directory; it does not
consult version metadata, and the normal resolve proves whether the requested
artifact actually exists. Add `--dry-run` to preview or `--no-resolve` to defer
the lockfile refresh.

See [Resolution](./REFERENCE.md#resolution-and-lockfile-contracts) and
[Updates](./REFERENCE.md#dependency-updates).

## Workspaces

```sh
zolt resolve --workspace
zolt build --workspace --all
zolt test --workspace --member apps/api
zolt package --workspace --members apps/api,tools
zolt tree --workspace --format json
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
zolt toolchain install java 21 --graalvm --native-image --refresh
zolt toolchain sync
zolt toolchain sync --refresh
zolt toolchain status
zolt toolchain list
zolt exec -- java -version
zolt shims install
```

Projects declare a concrete Java feature release and lock the newest resolved GA
patch from Temurin or GraalVM Community. Sync reuses the lock by default; pass
`--refresh` to update its exact patch. A separate test-runtime JDK can verify the
Java version users actually run.

See [Toolchains](./REFERENCE.md#java-toolchains).

## Frameworks

Zolt includes packaging or generated-source paths for Spring Boot, Quarkus,
OpenAPI, and Protobuf, with documented Micronaut and Vert.x examples.

```sh
zolt resolve
zolt package
zolt package --mode quarkus
zolt quarkus plan
zolt quarkus test-plan
```

Spring Boot archive modes are resolution inputs because they add locked loader
tooling. Set `[package].mode = "spring-boot"` (or `"spring-boot-war"`) in
`zolt.toml`, run `zolt resolve`, then use `zolt package`. The Spring Boot
examples already declare their package modes this way.

See [Frameworks](./REFERENCE.md#frameworks-and-generated-sources) and the
[examples](./examples/).

## Package

```sh
zolt package --plan
zolt package --mode jar
zolt package --mode uber-jar
zolt package --mode war
zolt package --mode quarkus
zolt native
```

Packages support sources, Javadoc, manifests, Maven metadata, and evidence.
`--mode` is a one-command standalone override only when the configured and
requested modes use the same resolution tooling. Jar, uber-jar, WAR, and Quarkus
layouts are in one resolution family; Spring Boot jar and Spring Boot WAR are
another. Crossing between those families fails closed: persist `[package].mode`, run
`zolt resolve`, and retry without `--mode`. Native builds validate that same
declared lock and use the resolved GraalVM toolchain. Their intermediate JVM
input and package evidence live under `[native].output/input` (by default
`target/native/input`), so `zolt native` never replaces the configured package
artifact, sidecar, supplemental artifacts, or evidence produced by
`zolt package`.

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
zolt config show --manifest
zolt config show --effective
zolt plan --target package
zolt plan --target test --format json
zolt classpath audit --format json
zolt ide model --format json
zolt licenses
zolt sbom
```

Human-readable output is the default. Commands that feed tools expose stable
JSON. The manifest configuration view shows only locally authored values; the
effective view composes workspace inheritance and built-in defaults. For a
project it labels each value's origin; at a virtual workspace root it reports
shared values once as root-authored, plus selected members. Neither view reads
machine-local user configuration.

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
rollback.

## More

- [FAQ](./FAQ.md)
- [Features](./FEATURES.md)
- [Reference](./REFERENCE.md)
- [Examples](./examples/)
- [Benchmarks](./docs/benchmarks/)
- [Breaking changes](./docs/breaking-changes.md)
