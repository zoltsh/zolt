# Zolt Usage Guide

Zolt is a Java build tool, not just a project generator. The smallest demo is
`zolt init && zolt test && zolt package`, but the current command surface also
covers dependency editing, reproducible resolution, workspaces, framework
packaging, generated sources, migration audits, quality gates, publishing, and
native release workflows.

## Start a Project

```sh
zolt init hello --group com.example --java 21
cd hello
zolt add com.google.guava:guava:33.4.0-jre
zolt add test org.junit.platform:junit-platform-console-standalone:1.11.4
zolt resolve
zolt test
zolt package
zolt run-package
```

Zolt writes a small `zolt.toml`, resolves a deterministic `zolt.lock`, compiles
sources, runs tests, and packages artifacts from the project model.

## Command Map

Common project commands:

```sh
zolt init NAME
zolt resolve
zolt build
zolt run -- ARGS
zolt test
zolt package
zolt run-package -- ARGS
zolt clean
zolt doctor
```

Dependency and lockfile commands:

```sh
zolt add GROUP:ARTIFACT:VERSION
zolt add test GROUP:ARTIFACT:VERSION
zolt add runtime GROUP:ARTIFACT:VERSION
zolt add provided GROUP:ARTIFACT:VERSION
zolt add processor GROUP:ARTIFACT:VERSION
zolt remove GROUP:ARTIFACT
zolt platform add GROUP:ARTIFACT:VERSION
zolt version set ALIAS VERSION
zolt version remove ALIAS
zolt resolve --locked
zolt resolve --offline
zolt tree
zolt why GROUP:ARTIFACT
zolt conflicts
zolt policy --format json
zolt classpath audit --format json
```

Build, test, and evidence commands:

```sh
zolt plan --target package
zolt plan --target test --format json
zolt test --test com.example.MainTest
zolt test --tests '*IntegrationTest'
zolt test --include-tag fast --exclude-tag slow
zolt test --suite smoke
zolt test --shard 1/4
zolt test --reports-dir target/test-reports
zolt test --profile-tests --profile-dir target/test-profiles
zolt coverage
zolt coverage --suite smoke --xml-report target/coverage/jacoco.xml
zolt test plan --shard-count 4 --format json
```

Toolchain commands:

```sh
zolt toolchain status
zolt toolchain status --json
zolt toolchain sync
zolt toolchain install java 21 --graalvm --native-image
zolt toolchain list
zolt toolchain available
zolt exec -- java -version
zolt toolchain global use java 21 --temurin
zolt toolchain global use java 21 --graalvm --native-image
zolt toolchain sync --global
zolt toolchain status --global
zolt toolchain global unset
zolt shims install
zolt shims status
zolt shims uninstall
```

Workspace commands:

```sh
zolt init platform --workspace
zolt resolve --workspace
zolt build --workspace --all
zolt test --workspace --member apps/api
zolt package --workspace --members apps/api,tools
zolt run --workspace --member tools -- release-notes
zolt coverage --workspace --all
zolt check --workspace --context ci --all
zolt clean --workspace --all
```

Packaging, publishing, and release commands:

```sh
zolt package --mode thin
zolt package --mode spring-boot
zolt package --mode spring-boot-war
zolt package --mode quarkus
zolt package --mode uber
zolt package --plan --format json
zolt publish --dry-run
zolt publish --dry-run --sbom
zolt publish --context release
zolt sbom
zolt sbom --workspace --output sbom.json
zolt licenses
zolt licenses --format json
zolt licenses --notices THIRD_PARTY.txt
zolt native
zolt native --workspace --member apps/zolt
zolt release-archive --target linux-x64 --binary target/native/zolt
zolt release-index --channel-manifest channels/zap.json --output index.json
zolt release-verify dist/zolt-0.1.0-linux-x64.tar.gz
```

Thin packaging writes a `.runtime-classpath` sidecar beside the application
jar. That file is a checkout-local launch convenience: its entries are the
workspace and artifact-cache paths whose bytes were verified when the package
was created. It is not a portable deployment manifest, and those paths can
become unavailable after moving the jar, deleting build outputs, or pruning the
cache. Use `zolt run-package` in the producing checkout, or repackage to refresh
the sidecar; deploy an archive package mode when the runtime must be
self-contained.

Uber-jar packaging merges runtime dependency classes into one archive. Duplicate
class entries fail the build by default; set `[package] uberDuplicates =
"first-wins"` to keep the first occurrence in the deterministic classpath order
instead (application output wins, then earlier dependencies) and report a per-jar
count of overridden entries. Uber jars that contain `META-INF/versions/` entries
are stamped `Multi-Release: true` automatically unless a `[package.manifest]`
entry sets that attribute explicitly.

```toml
[package]
mode = "uber"
uberDuplicates = "first-wins"
```

### Publishing to Maven repositories

`zolt publish` uploads the packaged artifact, a generated POM, and any
configured supplemental artifacts to a Maven-compatible repository. Configure
targets under `[publish]`, referencing `[repositoryCredentials]` for
authenticated repositories:

```toml
[publish]
releaseRepository = "company-releases"
snapshotRepository = "company-snapshots"

[publish.repositories.company-releases]
url = "https://repo.example.com/releases"
credentials = "company"

[publish.repositories.company-snapshots]
url = "https://repo.example.com/snapshots"
credentials = "company"
```

`zolt publish --dry-run` previews target routing, artifact evidence, the
generated POM, checksum sidecars, and any blockers without uploading anything.

Every uploaded file — the main artifact, each supplemental artifact, and the
POM — is accompanied by `.md5`, `.sha1`, and `.sha256` checksum sidecars, the
Maven repository standard for integrity verification. The sidecars contain the
bare lowercase hex digest and are listed in the `--dry-run` output.

Sources and Javadoc jars are produced by `zolt package` when enabled and are
published as supplemental artifacts with the standard `-sources.jar` and
`-javadoc.jar` classifiers:

```toml
[package]
sources = true
javadoc = true
```

The generated POM is enriched from `[package.metadata]`. Alongside `name`,
`description`, `url`, `license`, `developers`, `scm`, and `issues`, publish emits
`<packaging>` (derived from the package mode), a license `<url>`, richer SCM
elements, and structured developers with `<id>`, `<email>`, and `<organization>`:

```toml
[package.metadata]
name = "Example Library"
description = "A reusable Java library."
url = "https://example.com/library"
license = "Apache-2.0"
licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
scm = "https://github.com/example/library"
scmConnection = "scm:git:https://github.com/example/library.git"
scmDeveloperConnection = "scm:git:ssh://git@github.com/example/library.git"
scmTag = "v1.0.0"

[package.metadata.developer.ada]
name = "Ada Lovelace"
email = "ada@example.com"
organization = "Example Inc"
url = "https://example.com/ada"
```

`zolt publish --dry-run --central` reports Maven Central readiness: it checks the
release version, the required POM metadata (name, description, url, license
name+url, an identifiable developer, scm url+connection), the sources and Javadoc
jars, GPG signatures, and checksums, printing an actionable next step for each
unmet requirement. The check is opt-in, so it never blocks publishing to internal
repositories.

Enable `[publish.signing]` to attach a detached GPG signature (`.asc`) to every
uploaded artifact and to the POM. Publish shells out to the `gpg` binary
(`--batch --detach-sign --armor`); `keyId` selects the signing key and, when a key
requires a passphrase, `passphraseEnv` names an environment variable holding it —
the passphrase is fed to gpg over stdin, never the command line. Without
`passphraseEnv`, signing relies on `gpg-agent`. Secrets are referenced by
environment-variable name only:

```toml
[publish.signing]
enabled = true
keyId = "3AB1C2D3E4F5A6B7"
passphraseEnv = "ZOLT_SIGNING_PASSPHRASE"
```

The assembled bundle is a deterministic zip — entries are sorted and stamped with a
fixed time — so an **unsigned** bundle is byte-for-byte reproducible from reproducible
inputs. GPG signatures embed a creation time, so a **signed** bundle is reproducible
only when both that time and the key are pinned: set `SOURCE_DATE_EPOCH` (epoch seconds,
the same reproducible-build anchor Zolt honors for SBOM timestamps and build provenance)
to freeze each `.asc` signature's creation time, and set `keyId` so the same key signs in
every environment. When `SOURCE_DATE_EPOCH` is set, publish requires `keyId` and fails
with an actionable error otherwise, because gpg's default-key selection can differ
between keyrings. Without `SOURCE_DATE_EPOCH`, signatures carry wall-clock time and signed
bundles vary between runs, while the artifacts, checksums, and unsigned bundle stay
reproducible. The frozen signing time must be on or after the signing key's creation —
normally true of a release commit — or gpg refuses to make a signature that would predate
the key.

To publish to Maven Central through the Sonatype Central Portal, configure
`[publish.central]`. `tokenEnv` names an environment variable holding the base64
`user:password` Portal user token (sent as `Authorization: Bearer <token>`);
`publishingType` is `user-managed` (validate and wait for a manual publish) or
`automatic` (publish once validated); `baseUrl` may point at an enterprise mirror:

```toml
[publish.central]
tokenEnv = "ZOLT_CENTRAL_TOKEN"
publishingType = "automatic"
```

`zolt publish --central` assembles a bundle in Maven repository layout — every
artifact and the POM with checksums and GPG signatures — uploads it to the
Portal, and reports the deployment id. It blocks unless every Maven
Central readiness requirement is satisfied. `zolt publish --dry-run --central`
assembles and lists the bundle locally and runs the readiness check without any
network access.

By default the upload returns as soon as the bundle is accepted. Add `--wait` to
poll the deployment until it reaches a terminal state and fail the command if it
does not publish. For `publishingType = "automatic"`, `--wait` returns once the
deployment is `PUBLISHED` (live on Maven Central) and errors on `FAILED`, quoting
the Portal's reported detail. For `publishingType = "user-managed"`, validation is
terminal: `--wait` returns once the deployment is `VALIDATED` and reminds you to
release it from the Portal. `--wait-timeout <seconds>` bounds the wait (default
`300`); on timeout the command errors with the deployment id so you can check its
progress later in the Central Portal.

### Publishing a BOM and a workspace family

A BOM (bill of materials) publishes a curated version set — workspace members at
their locked versions plus chosen third-party pins — as the platform other teams
import. Author it as a dedicated workspace member whose `zolt.toml` has a `[bom]`
section; the section's presence implies `[package].mode = "bom"`.

```toml
[project]
name = "platform-bom"
group = "com.acme.platform"
version = "1.4.0"

[bom]
members = true                 # every workspace member, or an explicit ["core", "http"] list

[bom.versions]                 # mirrors [versions]: fixed literals or { versionRef = "netty" }
"org.postgresql:postgresql" = "42.7.4"

[bom.imports]                  # mirrors [platforms]: emitted as <type>pom</type><scope>import</scope>
"com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }
```

A BOM does not compile and has no jar: its artifact is the generated
`<dependencyManagement>` POM. `zolt build`/`package --workspace` skip its compile
wave and write `target/publish/<name>-<version>.pom` with package evidence.
Declaring dependencies, sources, javadoc, tests, or a manifest on a BOM is a
config error, and `zolt run`/`run-package` error actionably.

`zolt publish --workspace` publishes the whole family in one operation, using the
same per-member publication plan as a single-project publish. It runs an offline
Phase 1 first — per member it resolves config, projects a per-member lock,
generates and validates the POM (inter-member dependencies render at their locked
versions), resolves the member's real primary artifact through the framework-aware
package planner (a Quarkus fast-jar's runner jar, a WAR's archive — never a
synthesized `<name>-<version>.jar`), and checks readiness. For a plain-repository
publish Phase 1 then materialises the ENTIRE upload set before the first request: it
applies the same repository-URL policy Phase 2 uses, resolves every credential, runs
the signing preflight (gpg, key, and passphrase), and stages every checksum and
detached signature under `target/zolt-publish/staging`. Every blocker across every
member — a URL-policy violation or an unmet signing prerequisite included — is
aggregated into one report, and if anything is blocked nothing uploads.

Phase 2 uploads pre-staged bytes only, so a mid-member signing or checksum failure
is structurally impossible once uploads begin. Maven Central receives one atomic
family bundle (one deployment id); a plain repository receives a dependency-ordered
sequential upload (providers first, the BOM last) that authenticates every request
with the repository's configured `[repositoryCredentials]`. Each file is uploaded
idempotently — a path already holding the same bytes is skipped, so a resumed
publish re-PUTs nothing an immutable release repository would reject, while a path
holding different bytes is a hard, actionable conflict. On failure the publish
writes durable resume state to `target/zolt-publish/resume-state` (the completed
repository paths, a hash of the plan, and the semantic options) and emits an exact
resume command using the hidden `--resume-members`. Re-running that command resumes
only the members that did not finish and uploads only the files that did not land; a
`--resume-members` run without matching state, or against a plan that has since
changed, refuses and directs you to re-run the full publish rather than resume
blindly. Members publish at a uniform family version by default;
`--allow-mixed-versions` pins each member at its own version.

`--sbom` attaches a per-member CycloneDX SBOM (each jar member gets its own; the
BOM, having no resolved graph, gets none). `--central --wait` polls the family
deployment to a terminal state, honouring `--wait-timeout <seconds>`.

```sh
zolt publish --workspace --dry-run           # family Phase-1 preflight, no upload
zolt publish --workspace --sbom              # publish the family (+ per-member SBOMs)
zolt publish --workspace --central --wait    # one atomic Central bundle, then poll
zolt check --workspace --context ci --require-publish-dry-run   # CI gate
```

Consumers import the BOM through `[platforms]` and declare its members
version-less:

```toml
[platforms]
"com.acme.platform:acme-bom" = "1.4.0"

[dependencies]
"com.acme:acme-http" = {}     # version supplied by the imported BOM
```

`zolt sbom` on a BOM emits a metadata-only CycloneDX document (a BOM has no
resolved dependency graph) with a note on stderr, never an error. `zolt explain`
detects an existing Maven `dependencyManagement` BOM (`maven.bom.detected`) or a
Gradle `java-platform` project (`gradle.bom.detected`) and `zolt explain
--emit-toml` drafts a `[bom]` member from it, routing import-scope BOMs /
`platform(...)` imports to `[bom.imports]` and plain pins / `constraints { }` to
`[bom.versions]`.

Migration and integration commands:

```sh
zolt explain --source auto
zolt explain --source gradle --scorecard
zolt explain --source maven --blockers
zolt explain --emit-toml
zolt explain --emit-toml-output target/zolt-migration
zolt ide model --format json
zolt ide model --workspace --format json
zolt quarkus plan
zolt quarkus test-plan
```

Self-management commands for installer-managed native Zolt versions:

```sh
zolt self releases --channel zap
zolt self install VERSION --channel zap
zolt self exec VERSION -- version
zolt self prune --keep 3 --dry-run
zolt self update
```

## Project Configuration

A minimal project declares a Java package model, repositories, dependencies, and
source roots:

```toml
[project]
name = "hello-zolt"
version = "0.1.0"
group = "com.example"
java = "21"
main = "com.example.Main"

[repositories]
central = "https://repo.maven.apache.org/maven2"

[dependencies]
"com.google.guava:guava" = "33.4.0-jre"

[test.dependencies]
"org.junit.platform:junit-platform-console-standalone" = "1.11.4"

[build]
source = "src/main/java"
test = "src/test/java"
output = "target/classes"
testOutput = "target/test-classes"
```

Dependencies can be split by usage:

```toml
[dependencies]
"org.springframework.boot:spring-boot-starter-webmvc" = {}

[runtime.dependencies]
"com.h2database:h2" = {}

[provided.dependencies]
"jakarta.servlet:jakarta.servlet-api" = {}

[dev.dependencies]
"org.springframework.boot:spring-boot-devtools" = {}

[annotationProcessors]
"org.projectlombok:lombok" = {}

[test.dependencies]
"org.junit.jupiter:junit-jupiter" = "5.11.4"

[test.annotationProcessors]
"org.projectlombok:lombok" = {}
```

Version aliases, BOM/platform imports, exclusions, and constraints are first
class:

```toml
[versions]
spring = "4.0.6"

[platforms]
"org.springframework.boot:spring-boot-dependencies" = { versionRef = "spring" }

[dependencies]
"org.springframework.boot:spring-boot-starter-webmvc" = { exclusions = [
  { group = "org.apache.tomcat.embed", artifact = "tomcat-embed-core" }
] }
# classifier and type select a specific published artifact for a coordinate and
# are independent; both work in every dependency scope, with or without a
# version or platform-managed version.
"io.netty:netty-transport-native-epoll" = { version = "4.1.100.Final", classifier = "linux-x86_64" }

[dependencyPolicy]
exclude = [
  { group = "commons-logging", artifact = "commons-logging", reason = "Use Spring JCL and SLF4J bridges" }
]

[dependencyConstraints]
"org.apache.tomcat.embed:tomcat-embed-core" = { version = "11.0.21", kind = "strict", reason = "Servlet baseline" }
```

## Resolution and Lockfile Contracts

Zolt deliberately documents the parts of resolution that Maven leaves
ambient or historically shaped:

- Version conflicts use direct-preference plus newest-wins mediation, not
  Maven nearest-wins. Every mediation is recorded in `zolt.lock` as a
  `[[conflict]]` entry.
- Dynamic versions, version ranges, external SNAPSHOT dependencies, system
  scope, and uninterpolated `${...}` versions at point of use are rejected
  instead of resolved dynamically.
- Repositories are tried in alphabetical order of repository id because TOML
  table order is not a contract. The `source` recorded in `zolt.lock` makes
  the selected repository id part of the lockfile contract.
- Integrity is lockfile-pinned. Zolt records the SHA-256 of each fetched POM
  and JAR and verifies cached/build inputs against those lockfile hashes.
  Repository checksum sidecars are not fetched; the first download trusts the
  repository TLS channel.
- `zolt.lock` version 5 is deterministic: no timestamps, no absolute paths,
  stable ordering, and LF line endings. Version 2 added variant-qualified
  dependency edges and conflict identities; version 3 added source-scope-qualified
  dependency edges; version 4 adds member-qualified workspace dependency and
  policy graph facts so identical artifacts can retain different exclusion
  closures per member; version 5 carries optional-boundary and member-attributed
  conflict evidence and requires complete member graph facts for every qualified
  identity. Zolt still reads version 1 through 4 locks for compatible
  non-graph data, but graph commands refuse ambiguous legacy edges and direct you
  to regenerate the lock. Newly resolved locks use version 5 so older binaries
  cannot silently misread variant-, scope-, member-, or optional-qualified graphs. Newer
  lockfile versions require a newer Zolt before `zolt resolve --locked` can
  verify them.
- `zolt add`, `zolt remove`, and `zolt platform` rewrite `zolt.toml`. They
  warn before rewriting a file that contains comments because comments and
  custom formatting may be removed.
- `[publish]` is active project configuration for `zolt publish --dry-run`
  and uploads, and can reference `[repositoryCredentials]` entries for
  authenticated repositories.
- Version discovery is advisory-only. `maven-metadata.xml` is fetched only by
  `zolt outdated` and `zolt update`. Resolution — `zolt resolve`, `zolt resolve
  --locked`, `build`/`test`/`package`, workspace resolve, and `zolt explain
  verify` — never fetches, reads, or is influenced by version listings, and
  `zolt.lock` never records one. A discovered version enters a build only when
  written as a fixed literal into `zolt.toml` and re-resolved. This is enforced
  structurally (the resolve layer has no dependency on the discovery layer) and
  by a test asserting a resolve issues zero `maven-metadata.xml` requests.

### SNAPSHOT dependencies

A directly declared `-SNAPSHOT` dependency version — in `[dependencies]` and
every other dependency section, including `runtime`/`provided`/`dev`/`test`
dependencies and `annotationProcessors` — parses in `zolt.toml` and is decided
at resolve time rather than rejected while parsing, so the resolve layer is the
single authority on whether a SNAPSHOT is supported (`zolt add
group:artifact:1.0-SNAPSHOT` writes the declaration and lets resolve decide).
SNAPSHOTs are rejected by default because a moving version breaks the
reproducibility that `zolt.lock` promises. Two narrow exceptions are allowed
because they name artifacts that already exist on this machine rather than a
remote moving target: workspace-member coordinates a sibling depends on with
`{ workspace = "path" }` (built from source and locked with `source =
"workspace"`), and artifacts present in an enabled maven-local repository
overlay (`zolt resolve --repository-overlay maven-local`), which are SHA-256
pinned in `zolt.lock` with `source = "local-overlay:maven-local"` just like any
materialized artifact. Everything else — remote SNAPSHOT feeds,
`maven-metadata.xml`, and timestamped-snapshot resolution — stays unsupported by
design; a SNAPSHOT that is neither a workspace member nor present in an enabled
overlay is rejected at resolve time with guidance (from `zolt resolve`, `zolt
resolve --locked`, and `zolt explain verify`) rather than fetched from the
network. `[platforms]` and `[dependencyConstraints]` continue to reject
SNAPSHOTs while parsing.

## Dependency Updates

`zolt outdated` and `zolt update` are the only commands that consult remote
version listings (`maven-metadata.xml`). Discovery is advisory: it never changes
a build until a chosen version is written into `zolt.toml` and re-resolved.
SNAPSHOT versions are never suggested, ranges are never written, and toolchain
JDK versions are out of scope.

Listings are fetched from the same repositories, in the same
alphabetical-by-id order that resolution uses, and unioned across them (a
candidate's source is the first repository in that order that lists it). Version
comparison and stability are Zolt's own rules: a version is a prerelease only
when it carries a known qualifier (`alpha`/`a`, `beta`/`b`, `milestone`/`m`,
`rc`/`cr`, `ea`, `preview`, `dev`, `nightly`, `canary`, `pre`, `experimental`);
unknown qualifiers such as `-jre`, `-android`, `-incubating`, or calendar
versions are treated as releases. Listings live in a cache namespace kept separate from
the immutable artifact cache; online runs always refetch, falling back to the
cache with a staleness note only when a fetch fails, while `--offline` consults
the cache only.

### `zolt outdated`

Reports available updates for every version surface in `zolt.toml`: `[versions]`
aliases (with the coordinates each alias `governs`), literal-versioned
dependencies in every scope, `[platforms]`, `[annotationProcessors]` and their
test variants, `[dependencyConstraints]`, and `[generated.execTools]` /
protobuf / openapi tool coordinates. A versionRef-backed entry reports under its
alias rather than twice; SNAPSHOT literals and workspace-member dependencies are
ignored. Candidates are grouped by change class (patch, minor, major) with a
selected in-major target and a latest target. Run at a workspace root it reports
one block per member and notes coordinates shared across members.

Flags: `--format text|json` (JSON is schema v1 with stable keys and explicit
nulls), `--include-prereleases`, `--all` (also show up-to-date surfaces),
`--offline`, and optional selectors (a coordinate, alias, or section token).

    zolt outdated
    zolt outdated --format json
    zolt outdated --include-prereleases com.google.guava:guava

### `zolt update`

**Breaking change:** `zolt update` no longer updates the Zolt binary. It was
repurposed to update dependency versions in `zolt.toml`; binary self-update now
lives at `zolt self update`. See [Breaking changes](docs/breaking-changes.md).

The top-level `update` command writes version updates into `zolt.toml` using the
same discovery; binary self-update stays at `zolt self update`. By default it
moves each surface to the latest stable version within its current major;
`--latest` allows a major bump and `--patch`/`--minor`/`--major` set the
ceiling. `--include-prereleases` widens candidates (never to a SNAPSHOT), and
selectors scope which surfaces update.

Edits use only Zolt's existing mutation machinery and preserve dependency
metadata (exclusions, classifier, type, optional): a `[versions]` alias is the
primary lever, and updating one always warns with the full fan-out of
coordinates it changes; a versionRef-backed coordinate never has a literal
written over it. Literal exec-tool coordinate mutation is deferred, so those
surfaces are reported as skipped. `--dry-run` prints the semantic edit list (the
TOML writer re-serializes whole files, so text diffs are noise) and writes
nothing; `--format json` emits `edits[]`/`skipped[]`. After a successful write,
`zolt resolve` runs by default to keep `zolt.lock` consistent (`--no-resolve`
opts out), and a file containing comments is warned about before it is
rewritten.

    zolt update --dry-run
    zolt update --latest
    zolt update --minor com.example:lib

## Java Toolchains

Zolt can run `java`, `javac`, `jar`, and GraalVM `native-image` from a concrete
managed JDK instead of whatever happens to be on `PATH`.

Project-pinned toolchains are declared in `zolt.toml` and locked in `zolt.lock`:

```toml
[project]
java = "21"

[toolchain.java]
version = "21"
distribution = "graalvm-community"
features = ["native-image"]
policy = "prefer-managed"
```

Use `temurin` for JVM-only work, or `graalvm-community` with
`features = ["native-image"]` for native builds. Supported policies are:

- `prefer-managed`: use the Zolt-managed JDK when installed, but allow a
  matching system JDK.
- `require-managed`: never use ambient `JAVA_HOME` or `PATH`.
- `allow-system`: prefer a matching ambient JDK.

Sync installs the managed JDK into the default user store and writes stable
multi-platform toolchain metadata:

```sh
zolt toolchain install java 21 --graalvm --native-image
zolt toolchain sync
zolt toolchain list
zolt toolchain available
zolt toolchain status
zolt toolchain status --json
```

Use `zolt toolchain install java ...` when you want a managed Java version
available without first creating a project config. It installs into the same
shared user store and writes reusable global lock metadata, but it does not
change the global default. `zolt toolchain list` shows the active project or
global selection, lock entries, and installed catalog entries. `zolt toolchain
available` shows the bundled Temurin and GraalVM catalog matrix.

`zolt.lock` records the supported target matrix, so a macOS developer and Linux
CI do not rewrite the lock back and forth. The install itself stays local to the
machine under `~/.zolt/toolchains`; only the current host's archive is downloaded
when it is missing. Managed downloads are pinned to exact artifact URLs and
verified with SHA-256 checksums from the lock.

### Test-runtime toolchain

`javac --release N` guarantees your code only calls APIs that exist in Java N, but
`zolt test` still executes on the build toolchain — so a project that targets 17
while building on 21 never proves its code actually *runs* on 17. Pin a test-runtime
toolchain to close that gap: `--release` catches API misuse at compile time, and
running the tests on the target JRE catches everything else, completing the
cross-version story.

```toml
[project]
java = "17"

[toolchain.java]
version = "21"
distribution = "temurin"

[toolchain.java.test]
version = "17"
# distribution = "temurin"   # optional; defaults to [toolchain.java].distribution
```

`[toolchain.java.test]` sets its own `version` (and optional `distribution`),
inheriting `features` and `policy` from `[toolchain.java]`. It resolves, installs,
and locks through the same managed-toolchain machinery: `zolt toolchain sync`
installs it beside the build toolchain, `zolt toolchain status` and `zolt toolchain
list` show it, and it is recorded as an additive `[[toolchain.java]]` entry in
`zolt.lock` in the same per-platform, SHA-256-pinned format. An equal-version test
entry deduplicates against the main entry, so it neither grows the lock nor triggers
a second download.

When declared, `zolt test`, `zolt coverage`, and `zolt integration-test` compile on
the build toolchain but **run** the tests on the test-runtime JDK (the Jacoco agent
attaches on that JRE too). Without the section, tests run on the build toolchain and
nothing changes. Zolt rejects a test-runtime version older than `[project].java`
(it would fail with `UnsupportedClassVersionError`) and, when the toolchain is not
installed, points you at `zolt toolchain sync` — both surfaced by `zolt plan
--target test` and `zolt doctor`. Integration tests are the strongest use case:
exercise your build on the JDK your users actually run.

Build, run, test, package, and native commands use the resolved toolchain
automatically. `zolt exec -- ...` runs an arbitrary command with `JAVA_HOME` and
`PATH` pointed at the resolved JDK:

```sh
zolt exec -- java -version
zolt exec -- native-image --version
```

User global Java is a default, not a project override. Store it in
`~/.zolt/config.toml` with `[defaults.toolchain.java]`, or use the CLI:

```sh
zolt toolchain global use java 21 --temurin
zolt toolchain sync --global
zolt toolchain status --global
zolt exec --global -- java -version
```

When a project declares `[toolchain.java]`, project config wins. Outside a Zolt
project, `zolt exec -- java -version` can use the global default. Global lock
metadata lives beside the user config in `~/.zolt/global-toolchains.lock`, while
installed JDKs are shared in `~/.zolt/toolchains`.

Shell-global Java is opt-in through shims:

```sh
zolt shims install
export PATH="$HOME/.zolt/shims:$PATH"
java -version
```

The shims are small wrapper scripts for `java`, `javac`, `jar`, `javadoc`,
`jshell`, and `native-image`. Each wrapper calls `zolt exec -- <tool> ...`, so a
project toolchain still wins inside a Zolt project and the global default is
used outside one. Zolt does not edit shell profiles automatically.

## Enterprise Networks

Zolt runs behind corporate proxies, TLS-intercepting firewalls, and internal
mirrors. All of this is *transport* configuration: it changes how bytes are
fetched, never what is resolved, so it never affects `zolt.lock` or build
fingerprints. Machine-level settings live in `~/.zolt/config.toml` and
environment variables, never in the committed `zolt.toml`.

### HTTP(S) proxies

Zolt honors the standard proxy environment variables for both artifact
downloads and Java toolchain downloads:

```sh
export HTTPS_PROXY=http://proxy.corp.example:8080
export HTTP_PROXY=http://proxy.corp.example:8080
export NO_PROXY=.internal.example,localhost,127.0.0.1
```

Lowercase names (`https_proxy`, `http_proxy`, `no_proxy`) are also read and take
precedence over the uppercase forms. Java's built-in HTTP client ignores these
variables, so Zolt bridges them itself. The conventional Java system properties
are honored as a fallback when the matching environment variable is unset:

```sh
zolt resolve -Dhttps.proxyHost=proxy.corp.example -Dhttps.proxyPort=8080 \
  -Dhttp.nonProxyHosts='*.internal.example|localhost'
```

Precedence, per setting: environment variable first, then the Java system
property. `NO_PROXY`/`no_proxy` (falling back to `http.nonProxyHosts`) supports
exact hosts and domain-suffix matches — `example.com` and `.example.com` both
match `example.com` and `repo.example.com` — and `*` bypasses the proxy for
every host.

### Authenticated proxies

If your proxy requires HTTP Basic authentication, embed the credentials in the
proxy URL's userinfo. Zolt parses them and answers a `407 Proxy Authentication
Required` challenge with a `Proxy-Authorization` header — they are never silently
dropped:

```sh
export HTTPS_PROXY=http://alice:s3cr3t@proxy.corp.example:8080
```

Percent-encode any special characters in the password (`@` as `%40`, `:` as
`%3A`, and so on). The conventional `http.proxyUser`/`http.proxyPassword` (and
`https.*`) system properties are honored as a fallback when the proxy URL carries
no userinfo. Credentials are scoped to the proxy endpoint they are declared with
and are never offered to an origin server.

HTTP-origin downloads through an authenticating proxy work unconditionally. For
HTTPS origins the JDK opens a `CONNECT` tunnel and, by default, disables Basic
authentication on tunnels via `jdk.http.auth.tunneling.disabledSchemes`. When —
and only when — proxy credentials are configured, Zolt clears `Basic` from that
property before it builds its first HTTP client, so tunneled Basic auth works.
The JDK reads that property once per process; in the rare case another client was
already initialized, Zolt prints a one-time warning rather than failing silently,
and you can force the behavior yourself:

```sh
zolt resolve -Djdk.http.auth.tunneling.disabledSchemes=
```

### Custom CA trust

To trust a corporate TLS-interception root (or any private CA), point Zolt at a
PEM bundle of one or more certificates. Zolt *augments* the JDK default trust
store with these anchors, so public TLS keeps working alongside the corporate
root. Configure it in user-global config:

```toml
# ~/.zolt/config.toml
version = 1

[network]
caBundle = "~/.zolt/corp-ca.pem"
```

or override per invocation with an environment variable, which wins over the
config file:

```sh
export ZOLT_CA_BUNDLE=/etc/pki/tls/certs/corp-ca.pem
```

Machine-level trust belongs in user-global config or the environment, not in a
project's `zolt.toml`.

### Bearer / personal access token authentication

Repository credentials are referenced by environment-variable *name* only;
secret values are never stored in or read from config, and never written to
`zolt.lock` or command output. Alongside HTTP Basic (`usernameEnv` +
`passwordEnv`), a credential may use a bearer token / PAT via `tokenEnv`, which
sends `Authorization: Bearer <token>` on both downloads and publish uploads:

```toml
# zolt.toml
[repositories]
company = { url = "https://nexus.example.com/repository/maven", credentials = "company" }

[repositoryCredentials.company]
tokenEnv = "COMPANY_ARTIFACT_TOKEN"
```

`tokenEnv` is mutually exclusive with `usernameEnv`/`passwordEnv`; setting both
is a configuration error. Credentialed remote repositories must use HTTPS.

### Java toolchain mirrors

Zolt's bundled JDK catalog downloads from `github.com`. To route those downloads
through an internal mirror, set a mirror base that replaces the `github.com`
prefix. Configure it in user-global config:

```toml
# ~/.zolt/config.toml
[network]
toolchainMirror = "https://nexus.example.com/github"
```

or override with an environment variable, which wins over the config file:

```sh
export ZOLT_TOOLCHAIN_MIRROR=https://nexus.example.com/github
```

A GraalVM archive normally fetched from
`https://github.com/graalvm/graalvm-ce-builds/releases/download/...` is then
fetched from `https://nexus.example.com/github/graalvm/graalvm-ce-builds/...`.
The pinned SHA-256 from the toolchain lock is still verified against the
mirrored bytes, and the lock keeps the canonical upstream URL, so mirroring is
transparent to reproducibility. When a toolchain download fails with a network
error, the remediation names the proxy, CA, and mirror options above.

## Build Cache

Zolt fingerprints every module's compile inputs to skip recompilation when
nothing changed. That skip-gate is local: a clean checkout or a wiped `target/`
always recompiles. The build cache stores the compiled output keyed by those
same fingerprints, so a wiped or fresh checkout **restores** the classes instead
of recompiling them. It is opt-in and content-addressed.

Correctness beats hit rate: the cache is keyed on everything that determines the
compiled bytes, and any doubt is a miss that rebuilds. Every entry is verified
against its sidecar — requested key, scope, declared size, and archive SHA-256 —
before any output is touched, and a restore that cannot be fully verified is a
miss that rebuilds. A *verified* cache hit reproduces the bytes a compile would
produce. That guarantee holds against corruption and mismatched entries; for a
shared remote cache, the trust model below sets out what it does and does not
defend against.

### Enabling it

The build cache is a machine concern, like proxy and CA settings — it never
affects `zolt.lock` or what gets built, so it lives in `~/.zolt/config.toml`, not
in a committed `zolt.toml` (a `[buildCache]` table in a project `zolt.toml` is
rejected). It is disabled by default.

```toml
# ~/.zolt/config.toml
version = 1

[buildCache]
enabled = true
# Optional. Defaults to ~/.zolt/build-cache.
dir = "~/.zolt/build-cache"
# Optional cap in megabytes; the cache is pruned (least-recently-used) to fit.
# Defaults to 2048.
maxSizeMb = 2048
```

When enabled, `zolt build`, `zolt test`, and `zolt package` restore a module's
compiled classes on a fingerprint miss and store them after a real compile. In a
workspace each member is cached independently. Restores are reported distinctly:

```text
✔ Restored 80 main classes · build cache
```

Pass `--no-build-cache` to any of those commands to bypass the cache for one run
(neither restore nor store). `--timings` records the per-module cache outcome
(`restored`, `stored`, or `uncacheable`).

### Inspecting and pruning

```sh
zolt cache status              # location, entry count, size, and cap
zolt cache prune               # evict least-recently-used entries to the cap
zolt cache prune --max-size-mb 512   # prune to a smaller cap for this run
```

The cache also auto-prunes opportunistically after a store when it exceeds the
cap.

### Remote cache

A remote HTTP endpoint lets a team or CI share compiled output. It is dumb HTTP —
`GET <url>/<key>` reads a blob (404 is a miss), `PUT <url>/<key>` writes one — so
any Artifactory or Nexus generic repository works. On a local miss Zolt fetches
from the remote, verifies the blob, copies it into the local cache, and restores
from there. Reads are the default; pushing is opt-in, so developers populate from
the shared cache while CI writes to it.

```toml
# ~/.zolt/config.toml
version = 1

[buildCache]
enabled = true

[buildCache.remote]
url = "https://nexus.example.com/repository/zolt-build-cache"
credentials = "buildCache"   # optional; names a [repositoryCredentials] block
push = false                 # devs read; set true (or ZOLT_BUILD_CACHE_PUSH=1) on CI

# Credential definitions may live in user-global config for machine/CI use. Only
# environment-variable *names* are stored — never secret values.
[repositoryCredentials.buildCache]
tokenEnv = "ZOLT_BUILD_CACHE_TOKEN"   # bearer; or usernameEnv + passwordEnv for basic
```

Auth reuses the repository credential model: `tokenEnv` sends
`Authorization: Bearer <token>`, and `usernameEnv`/`passwordEnv` send HTTP Basic.
A credentialed remote must use HTTPS. Set `push = true` in config or export
`ZOLT_BUILD_CACHE_PUSH=1` for one run (the typical CI setup).

The remote never fails a build. A read problem is a miss that rebuilds; an upload
problem is a warning and the build continues; an unauthorized (`401`/`403`) push
surfaces one actionable warning per build. `--offline` drops the remote tier
entirely while the local cache keeps serving.

### Cache integrity and trust model

The remote cache is **trusted build infrastructure**, not a public download. Its
integrity checks defend against corruption and mismatched entries — a truncated
blob, a torn upload, a sidecar that names a different key — but they are **not** a
defense against a party with malicious write access to the cache. Operate a shared
cache accordingly: restrict write access to CI, treat entries as immutable
(content-addressed keys never change meaning), and serve reads over HTTPS.

Within that model every restore is defended before it can affect a build:

- **Bounded download.** The archive and its sidecar stream to temporary files
  under hard size caps (the sidecar a small fixed cap; the archive the configured
  `maxSizeMb`, or 512 MB when the cache is uncapped). A body that exceeds its cap
  is aborted and treated as a miss, so the client never buffers an unbounded blob.
- **Full identity check.** Before any output is touched, the sidecar must match
  the requested entry on every axis — key, scope, declared size, and archive
  SHA-256. Any mismatch is a miss that deletes the local copy and logs one warning
  naming what failed.
- **Bounded, transactional extraction.** The archive is extracted into a sibling
  staging directory under entry-count, per-entry, and total-decompressed-size
  limits (so a "zip bomb" aborts) and with a path-traversal guard, then swapped
  into place atomically. A failure at any point leaves the previous output exactly
  as it was — never partial — so the build proceeds as a clean miss and recompiles.

Because the SHA sidecar is served by the same remote as the archive, it detects
corruption, not tampering: a writer who can replace the archive can replace the
sidecar too. That is why write access is the security boundary for a shared cache.

**Planned — signed entries.** A future opt-in `hmacKeyEnv` under
`[buildCache.remote]` will name an environment variable holding a shared secret;
producers HMAC the sidecar (metadata and SHA) at store time and consumers verify
it at restore, adding tamper-evidence on top of the integrity checks for caches
whose write access cannot be fully trusted. Following the credential convention,
only the env-var *name* is stored, never the secret. This is not yet implemented;
until it ships, rely on restricting write access.

### The key, and the JDK

The cache key is the inputs-only compile fingerprint (sources, classpath,
compiler settings, generated sources, resources — everything except the expected
output classes) plus the compile scope and the resolved JDK identity. The JDK is
part of the key because `javac` can emit different bytecode across JDK majors even
for the same `--release`; the on-disk skip-gate does not need it, but a shared
cache does. Classpath dependencies contribute a content hash of their compiled
bytes (not their location), so a build keys identically whether a dependency was
itself compiled or restored, and regardless of where artifacts live on disk.

### Incremental state (v1 tradeoff)

Zolt's warm incremental compiler keeps machine-local state files
(`.zolt-incremental-*.state`) that record per-source ownership using absolute
paths. Those are never cached. A restored module therefore has no incremental
state: the skip-gate works immediately (a no-op rebuild still skips), but the
**next source edit does one full recompile**, which re-establishes incremental
state and re-stores the cache entry. Subsequent edits are incremental as usual.
The build cache is aimed at clean and CI builds; warm incremental development
stays entirely local, and the cache is not consulted while incremental state is
present.

### What is never cached

A module whose output is not a pure function of its hashed inputs is excluded
from the cache entirely (store and restore). Today that means any module with a
`cache = "none"` exec generated-source step: such a step runs against an oracle
Zolt cannot hash (a live database, a clock, the network), so its output is not
reproducible. Over-approximating this taint is the safe direction.

## Workspaces

Workspace roots describe member projects and default selections:

```toml
[workspace]
name = "workspace-app"
members = ["apps/api", "modules/core", "tools"]
defaultMembers = ["apps/api"]

[repositories]
central = "https://repo.maven.apache.org/maven2"

[commands.tasks.release-notes]
description = "Run the Java tools member release-notes command"
cmd = ["zolt", "run", "--workspace", "--member", "tools", "--", "release-notes"]
```

Workspace commands resolve one root lockfile and run selected members in
dependency order.

Workspace repositories may use the same structured credential references as a
project repository. The root policy is inherited by every member, so private
company repositories do not need to be repeated in each member:

```toml
[repositories]
company = { url = "https://repo.example.com/maven", credentials = "company" }

[repositoryCredentials.company]
tokenEnv = "COMPANY_ARTIFACT_TOKEN"
```

Members may add repositories. A matching member string repository cannot remove
authentication inherited from the workspace root, and conflicting credential
references fail with an actionable configuration error.

Workspace mediation is artifact-variant-aware: a plain JAR, classified JAR, and
typed artifact of one GA mediate independently. Direct-preference and
newest-wins are applied workspace-wide within each variant lane, and
`[dependencyPolicy].failOnVersionConflict` is enforced for every member affected
by a resulting conflict. Cross-member mediation never silently chooses between
another member's direct dependency and a member's strict
`[dependencyConstraints]` pin: if the workspace-selected version would override
that strict pin, resolve fails and requires the declarations to be aligned.
Within one member, the ordinary single-project rule remains unchanged: its own
direct declaration takes precedence over its own strict transitive constraint.

Version 5 workspace locks retain the version 4 per-member graph facts when members resolve
the same artifact bytes with different exclusions or policy effects. Classpath
and SBOM projections use those member-qualified facts, optional dependency
closures stay local to their declaring member, and whole-workspace SBOMs use
member-qualified `bom-ref` contexts when a shared PURL has different outgoing
graphs. If repositories serve
different artifact or POM bytes for the same selected identity, workspace resolve
fails instead of choosing whichever member happened to resolve first.
Root-only workspaces (`members = ["."]`) use the same qualified lock contract:
project fingerprints are retained while packages, exports, and optional facts
are attributed to `"."`. Workspace `package-contents` quality also uses each
member's actual package/runtime closure and supports BOM POM plans; it does not
inspect unrelated sibling JARs from the aggregate lock.

## Tests and Coverage

Zolt runs JUnit Platform based tests and can compile Java and Groovy test
sources when configured. That covers examples such as JUnit Jupiter, JUnit
Vintage, and Spock:

```toml
[test.dependencies]
"org.apache.groovy:groovy" = "4.0.22"
"org.spockframework:spock-core" = "2.3-groovy-4.0"
"org.junit.platform:junit-platform-console-standalone" = "1.11.4"

[test.sources]
groovy = ["src/test/groovy"]
```

Test commands support class/method selection, glob patterns, JUnit tags, JVM
arguments, XML reports, deterministic shards, named suites, and optional profile
history used for shard balancing.

### Coverage Floors

A `[coverage]` section declares the minimum Jacoco coverage a project accepts.
Each floor is an optional percentage (`0`–`100`); omit a metric to leave it
ungated:

```toml
[coverage]
minLine = 88.0
minBranch = 74.0
# minInstruction and minMethod are also supported.
```

After `zolt coverage` writes its report, floors are checked against the report's
totals; any metric below its floor fails the command with a non-zero exit and an
actionable message naming the metric, its actual coverage, and the floor. With no
`[coverage]` section, coverage behavior is unchanged. `zolt check --context ci`
applies the same floors when a `jacoco.xml` report is present under the coverage
directory. In a workspace, floors in the root `zolt.toml` gate the aggregate
`zolt coverage --workspace --all` report, while a member's own `[coverage]`
governs that member's solo `zolt coverage` run.

## Frameworks and Generated Sources

Zolt has project-model support for common Java application shapes:

- Spring Boot web applications, executable jars, WAR-style packaging, build
  metadata, resources, and Spring Boot package modes.
- Quarkus fast-jar packaging and Quarkus augmentation/test plan inspection when
  `[framework.quarkus]` is enabled.
- Micronaut-style annotation processor workflows.
- Vert.x applications with platform BOMs and dependency exclusions.
- OpenAPI generated Java sources with tool versioning and presets.
- Protobuf/gRPC generated Java sources.
- Generic exec steps that run a pinned tool — a resolver-locked `jvm` tool, a
  PATH `process` tool, or the member's own `project` classpath — to produce Java
  sources or resources consumed by the build.
- Library-style canaries such as Commons CLI, HikariCP, and SLF4J workspace
  modules.

Generated-source configuration can look like this:

```toml
[generated.openapiTool]
coordinate = "org.openapitools:openapi-generator-cli"
version = "7.11.0"

[generated.openapiPresets.spring-api]
generator = "spring"
library = "spring-boot"
apiPackage = "com.example.generated.api"
modelPackage = "com.example.generated.model"
invokerPackage = "com.example.generated"

[generated.main.public-api]
kind = "openapi"
language = "java"
input = "src/main/openapi/public-api.yaml"
output = "target/generated/sources/openapi/public-api"
preset = "spring-api"
```

Protobuf/gRPC configuration can look like this:

```toml
[versions]
protobuf = "4.28.3"
grpc = "1.68.1"

[generated.protobufTool]
protocCoordinate = "com.google.protobuf:protobuf-java"
protocVersionRef = "protobuf"
grpcPluginCoordinate = "io.grpc:grpc-api"
grpcPluginVersionRef = "grpc"

[generated.main.greeter]
kind = "protobuf"
language = "java"
inputs = ["src/main/proto/greeter.proto"]
output = "target/generated/sources/protobuf"
```

### Exec Steps

An exec step runs a pinned tool on declared inputs to produce one owned output
that the build consumes — the same reproducible machinery as OpenAPI/protobuf,
with the tool identity lifted into configuration. A named tool under
`[generated.execTools.<name>]` (stage 1: `runner = "jvm"`) resolves its
`coordinates` into the locked `tool-exec` scope, never onto application
classpaths, and Zolt launches `<managed java> -cp <locked jars> <mainClass>
<args>` in a sandboxed working directory with a curated environment.

```toml
[versions]
jooq = "3.19.15"
postgres = "42.7.4"

[generated.execTools.jooq]
runner = "jvm"
coordinates = [
    { coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" },
    { coordinate = "org.postgresql:postgresql", versionRef = "postgres" },
]
mainClass = "org.jooq.codegen.GenerationTool"

[generated.main.jooq-model]
kind = "exec"
tool = "jooq"
args = ["src/main/jooq/config.xml"]
inputs = ["src/main/jooq/config.xml", "src/main/resources/db/schema.sql"]
output = "target/generated/sources/jooq"
produces = "java-sources"          # or "resources", with into = "static"
cache = "content"                  # default (only value in stage 1)
```

A step's position is derived entirely from its declared IO — there is no
anchor, `after`, or `dependsOn`. `produces = "java-sources"` (or
`"test-sources"`) joins that scope's compile source roots; `produces =
"resources"` joins resource copying, optionally under an `into` subtree. An
input equal to or under another exec step's declared output creates an ordering
edge; steps run serially in the topological order of those edges, with ties
broken alphabetically by id, and a cycle is a configuration error.

Determinism follows the OpenAPI cache: an exec step re-runs only when its
fingerprint changes — locked tool jar hashes, argv, the content of its expanded
inputs, its literal `env`, and produces/into — and its output bytes are hashed
into the module build fingerprint so a changed output invalidates exactly its
consumers. `args` is an argv array Zolt never interprets as a shell string, and
every declared path is real-path-contained under the project root. `zolt plan`
shows each step's derived position, tool identity, inputs/outputs, and cache
policy, and `zolt check` validates the steps.

Beyond the resolver-locked `jvm` runner, a tool can run a PATH binary. A
`process` tool runs a `binary` discovered on the curated PATH; because PATH
bytes are unprovable it must set `allowUnpinnedTool = true`, and its identity in
the fingerprint is the binary name plus the probed `versionCommand` stdout (an
optional `versionExpect` semver range fails fast on a wrong version). `cwd`
sandboxes the working directory, `timeoutSeconds` bounds the run (default 600),
and `secretEnv` injects a value read from a named ambient variable at run time
without ever writing that value to config, the lockfile, fingerprints, or logs.

Because Zolt never reads a secret's value into the content fingerprint, a step
that declares `secretEnv` cannot use the default `cache = "content"` — a changed
secret would otherwise silently reuse stale output. Zolt rejects that
combination and offers two honest paths: set `cache = "none"` (the step always
runs and its output is non-hermetic, excluded from the shared build cache), or
add a non-secret `cacheSalt = "<token>"` that you bump whenever the
secret-derived output must change. The salt is your assertion and participates in
the fingerprint; Zolt does not read secret values into fingerprints. `inheritEnv`
is different: Zolt folds a digest of each inherited variable's actual runtime
value into the fingerprint (never the raw value), so a changed inherited value,
DB endpoint, or token re-runs the step on its own.

```toml
[generated.execTools.node]
runner = "process"
binary = "npm"
versionCommand = ["npm", "--version"]   # probed stdout enters the fingerprint
versionExpect = ">=10 <11"              # optional fail-fast guard
allowUnpinnedTool = true                # required: PATH bytes are unprovable

[generated.main.frontend-build]
kind = "exec"
tool = "node"
cwd = "web"                             # sandboxed, project-relative working directory
args = ["run", "build"]
inputs = ["web/package-lock.json", "web/src/**", "web/vite.config.ts"]
output = "web/dist"
produces = "resources"
into = "static"
timeoutSeconds = 900                    # per-step wall-clock bound (default 600)
cacheSalt = "frontend-1"               # required with secretEnv on cache = "content"; bump on secret-derived change
[generated.main.frontend-build.env]
NODE_ENV = "production"
[generated.main.frontend-build.secretEnv]
NPM_TOKEN = "CI_NPM_TOKEN"              # target env name = source var; the value is never written down
```

`tool = "project"` is a built-in pseudo-tool: it launches `mainClass` on the
member's own compiled classes plus resolved runtime classpath (Maven
`exec:java` parity). Declaring an input under `target/classes` — or using
`tool = "project"`, which always does — schedules the step after compile, so it
may produce only `resources`, `test-resources`, or `intermediate`; producing
main sources from main classes is a cycle and a plan blocker. `cache = "none"`
is the honest non-determinism escape for a step Zolt cannot fingerprint (a live
database, a network call): it always runs, is a hard error under `--offline`,
fails `zolt check --require-offline-ready`, and stamps `hermetic = false` into
package evidence.

```toml
[generated.main.build-info]
kind = "exec"
tool = "project"
mainClass = "com.example.build.BuildInfoWriter"
inputs = ["target/classes"]             # under the compile output: scheduled after compile
output = "target/generated/resources/build-info"
produces = "resources"
cache = "none"                          # always-run; excluded from --offline; hermetic = false
```

## Generated Producer Contract

Every generated-source producer — the OpenAPI and protobuf built-ins and the
generic exec step — obeys one contract, documented here the way resolution is,
because a build tool that runs third-party tools must be explicit about what
those tools may read and produce:

- Inputs are exactly the declared closure. Zolt expands the `inputs` globs
  itself, sorts them, and hashes their content into the step fingerprint;
  nothing outside that closure is an input, and a step never treats the network
  or ambient state as a hidden input.
- Output is exactly the owned directory. A step writes only under its single
  declared `output`. After a cacheable step runs, any file whose mtime advanced
  under the step's `cwd` but outside that output is an undeclared-output error
  naming the path.
- Tool identity is pinned or probed-advisory, and the fingerprint records which.
  A `jvm` tool is pinned: its coordinates resolve into the locked `tool-exec`
  scope, never onto application classpaths, and the SHA-256 of each jar is in
  `zolt.lock` and the fingerprint. A `process` tool is probed-advisory: PATH
  bytes are unprovable, so its identity is the binary name plus the probed
  `versionCommand` stdout, and it requires `allowUnpinnedTool = true` to say so.
- Skip is fingerprint-exact. A step re-runs only when its fingerprint changes —
  tool identity, argv, expanded input content, env names and literal values, a
  digest of each `inheritEnv` variable's actual runtime value, the non-secret
  `cacheSalt`, `cwd`, and `produces`/`into` — and its output bytes are hashed
  into the module build fingerprint, so a changed output invalidates exactly its
  consumers while stable output lets them skip even after an always-run step.
- `cache = "none"` is honest non-determinism, not a cache miss. It always runs,
  is excluded from `--offline` as a hard error, fails `zolt check
  --require-offline-ready`, and stamps `hermetic = false` into package evidence.
  Zolt never fabricates a cache key for an oracle it cannot hash; the sanctioned
  reproducible paths are committed DDL/schema or `cache = "none"`.
- Environment is curated, never inherited. A step sees only OS essentials
  (`PATH`, `HOME`), the explicit `inheritEnv` allowlist, the literal `env`
  table, and `secretEnv` indirection. `secretEnv` carries only names
  (`TARGET_NAME = "SOURCE_ENV_NAME"`); the secret value is read at run time and
  never appears in config, `zolt.lock`, fingerprints, plans, or logs. Because
  the secret value is never fingerprinted, `secretEnv` forbids the default
  `cache = "content"` unless you set a non-secret `cacheSalt` you bump on change;
  otherwise use `cache = "none"`. For `inheritEnv`, Zolt does fold a digest of
  the variable's actual runtime value into the fingerprint (an unset variable
  gets a distinct marker), so a changed inherited value re-runs the step.
- The refusals are the product. No shell strings or `sh -c` (argv arrays only,
  Zolt owns glob expansion); no lifecycle hooks or phase attachment; no in-place
  source mutation (formatters route to `zolt task` plus a dirty-tree check); no
  build-time network fetch as an input mechanism (a step that reaches the
  network owns `cache = "none"`); and no user classes in the build JVM.

## Quality and CI

`zolt check` is the umbrella for Zolt-owned quality checks. It can run local or
CI-oriented contexts, validate reports and coverage outputs, require package
evidence, require publish dry-run evidence, and check offline readiness.

```sh
zolt check --context local
zolt check --context ci --reports-dir target/test-reports --coverage-dir target/coverage
zolt check --context ci --require-package --require-publish-dry-run
zolt check --context ci --require-offline-ready
zolt check --format json
```

The CLI also has global output controls for automation:

```sh
zolt --quiet test
zolt --color never --progress never check --format json
zolt --timings --timings-format json package
```

## Supply Chain

Zolt reads the resolved dependency graph straight from `zolt.lock` to produce a
software bill of materials and license reports. Both commands are fully offline:
a missing cached POM is reported as `UNKNOWN`, never fetched.

`zolt sbom` writes a CycloneDX 1.5 BOM to stdout (or to `--output <path>`). The
graph, hashes, and per-member attribution all come from the lockfile — there is
no re-resolution. Output is byte-reproducible: the `serialNumber` is a
deterministic UUIDv5 of the lockfile fingerprint, and `metadata.timestamp` is
omitted by default (set it with `--timestamp <iso8601>`, `--timestamp now`, or
`SOURCE_DATE_EPOCH`).

```sh
zolt sbom
zolt sbom --output sbom.json
zolt sbom --include-test --include-provided --include-tools --include-dev
zolt sbom --workspace
```

Compile and runtime dependencies are included by default as CycloneDX
`required`; provided, dev, test, and tooling scopes are opt-in via the
`--include-*` flags and emitted as `optional`. `--workspace` aggregates the whole
workspace into one BOM: a root workspace component, each member as a library
component, and external dependencies deduped with member→dependency edges from
the lockfile.

`zolt licenses` groups dependencies by license, normalizing raw Maven license
names and URLs to SPDX identifiers. Unrecognized licenses stay `UNMAPPED` (raw
name kept, never guessed); dependencies with no readable license are `UNKNOWN`.
`--notices <path>` writes a deterministic `THIRD_PARTY` notices file.

```sh
zolt licenses
zolt licenses --format json
zolt licenses --notices THIRD_PARTY.txt
```

Enforce a license policy under `[dependencyPolicy.licenses]`. A license is
permitted iff its id is not in `deny` and (`allow` is empty or its id is in
`allow`) — deny always wins, and a non-empty allow-list is authoritative. The
`unknown` strictness (`fail`, `warn`, or `allow`; default `warn`) governs
dependencies with unresolved licenses.

```toml
[dependencyPolicy.licenses]
allow = ["Apache-2.0", "MIT", "BSD-3-Clause"]
deny = ["GPL-3.0-only"]
unknown = "warn"
```

`zolt check --check license-policy` (also part of the CI context) fails the build
when a compile/runtime dependency violates the policy, naming the dependency, the
license, and the policy line.

`zolt publish --sbom` attaches a CycloneDX SBOM to the publish as a supplemental
artifact (classifier `cyclonedx`, extension `json`). It rides the existing
supplemental planner, so checksums and signing apply to it uniformly; it is off
by default.

## Migration Explain

`zolt explain` statically audits Maven and Gradle projects. It can print a text
or JSON report, a migration readiness scorecard, a blocker report, or draft
`zolt.toml` files. The fixtures under `examples/migration-explain` include
Maven simple/multimodule and Gradle simple/multiproject/enterprise-style inputs.

Useful prompts for migration work:

```sh
zolt explain --source auto
zolt explain --source gradle --scorecard
zolt explain --source maven --blockers
zolt explain --emit-toml
zolt explain --emit-toml-output target/zolt-draft
zolt explain --emit-toml --resolve-external-parents
```

By default the audit is fully offline, so a Maven project that inherits an
external parent (for example `spring-boot-starter-parent`) reports its inherited
dependency versions as unknown. Pass `--resolve-external-parents` to opt in to
fetching those external parent POMs and their imported BOMs over the network —
from Maven Central plus any HTTPS `<repositories>` declared in the POM chain,
cached under `~/.zolt/cache` for offline re-runs. Recovered versions are fixed
literals, so inherited `[dependencies]` and `[platforms]` resolve in the report
and in `--emit-toml`; anything dynamic (ranges, SNAPSHOT parents, unresolved
`${...}`) is surfaced as a review item instead of being guessed, and every
fetched coordinate is recorded in the audit.

Gradle BOM shapes map like their Maven counterparts. A `platform('g:a:v')` or
`enforcedPlatform(...)` import — Groovy or Kotlin DSL, a string coordinate or a
`platform(libs.x)` version-catalog reference, in any configuration — is emitted
under `[platforms]` rather than as a classpath dependency, and a version-less
dependency in a build file that imports a platform is drafted as platform-managed
`{}` with a review item to confirm the platform manages it. `enforcedPlatform`
maps like `platform` plus a note that Gradle's version-override semantics are only
approximated — the Zolt analog for a hard pin is a `[dependencyConstraints]` entry
with `kind = "strict"`, which the draft points at rather than auto-generates. A
`java-platform` project is recognized as a BOM (`gradle.bom.detected`), and
`zolt explain --emit-toml` drafts a `[bom]` member from it: `platform(...)` imports
become `[bom.imports]`, `constraints { }` pins (`api`/`runtime`) become
`[bom.versions]`, and a plain dependency declared under `allowDependencies()`
becomes a review item because a Zolt BOM carries no dependencies. Constraints the
static regexes cannot resolve (interpolated or computed versions) raise a signal
rather than being dropped.

### Verify a migration

`zolt explain verify` gives a factual, per-module comparison between what the incumbent
build (Maven or Gradle) resolves and what Zolt resolves, so a migration can be verified
instead of trusted. The incumbent is auto-detected — a `pom.xml` selects Maven, a Gradle
settings/build script selects Gradle — and `--source maven|gradle` overrides. For Maven it
runs `./mvnw` (else `mvn` on `PATH`) once as `dependency:tree`; for Gradle it runs
`./gradlew` (else `gradle` on `PATH`) once, requesting every project's `dependencies`
report in a single invocation. It then resolves the Zolt project/workspace with Zolt's own
resolver and reports — per module and per scope — what matched, what drifted in version,
and what appears on only one side.

```sh
# Compare the incumbent build and a Zolt project rooted in the same directory.
zolt explain verify

# Verify a Gradle migration (auto-detected when a settings/build script is present).
zolt explain verify --source gradle

# Point at a draft emitted by --emit-toml-output, and emit machine-readable JSON.
zolt explain verify --zolt-dir target/zolt-draft --format json
```

- Scopes compared: `compile`, `runtime`, `test`, `provided`. Maven and Zolt scope
  names map one to one. Scopes with no counterpart (Maven `system`; Zolt `dev`,
  `processor`, `test-processor`, `quarkus-deployment`, `tool-*`) are reported as
  per-module notes rather than counted as differences.
- Gradle configuration mapping: Gradle's classpaths are cumulative (`runtimeClasspath` and
  `testRuntimeClasspath` include the compile dependencies) whereas Maven and Zolt place each
  dependency in exactly one scope, so the compared scopes are recovered as set operations
  over the three resolvable classpaths — `compile` = `compileClasspath` ∩ `runtimeClasspath`,
  `runtime` = `runtimeClasspath` \ `compileClasspath`, `provided` = `compileClasspath` \
  `runtimeClasspath` (Gradle `compileOnly`, the Maven `provided` equivalent), and `test` =
  `testRuntimeClasspath` \ (`compileClasspath` ∪ `runtimeClasspath`). BOM/platform nodes and
  dependency constraints (`(c)`) are excluded so the resolved set stays jar-for-jar
  comparable with Maven's tree; `(*)` repeated subtrees are de-duplicated, `a:b:req ->
  resolved` conflict resolutions take the resolved version, and `project(":x")` dependencies
  are compared as ordinary resolved artifacts (mirroring how Maven lists reactor siblings).
  Annotation-processor and other non-classpath configurations are not compared, matching
  Maven's `dependency:tree` (which likewise omits processors).
- Categories per module × scope: matched (same `group:artifact[:classifier]` and
  version), version drift (same coordinate, different version — both reported),
  only-in-Maven, and only-in-Zolt. Zolt uses highest-version-wins mediation and Maven
  uses nearest-wins, so some drift is expected; the report states facts and counts and
  does not editorialize about equivalence.
- `--zolt-dir <path>` selects the Zolt project/workspace to resolve (default: the Maven
  project root when it has a `zolt.toml`). `--offline` resolves the Zolt side from the
  local cache only.
- `--repository-overlay maven-local` resolves the Zolt side through a user-local maven
  repository overlay, mirroring `zolt resolve --repository-overlay` — needed to verify
  overlay-backed projects, including directly declared `-SNAPSHOT` dependencies that
  resolve only from the overlay.
- Workspace members resolve with the workspace-root `[repositories]` and `[platforms]`
  merged in, matching `zolt resolve --workspace`, so shared root configuration is not
  reported as drift.
- Exit code is `0` when the resolved sets are identical across every module and scope,
  and non-zero when any module is one-sided or any scope shows drift or a one-sided
  artifact — so it can gate a migration in CI. `--format json` emits a stable schema
  (`schemaVersion` 1) with an additive `buildTool` field (`maven` or `gradle`); the JSON
  field names stay `maven*` across both tools for schema stability.

Both Maven and Gradle projects are supported. The comparison model, scopes, categories,
and JSON schema are shared; a Gradle build with a dynamic version, a missing included
build, or another unresolvable configuration fails the extraction with an actionable error
rather than a partial comparison, because a build that does not resolve cannot be verified.

## Examples in This Repository

The `examples/` directory is deliberately broad. It includes:

- `hello-zolt`: minimal project with an external dependency.
- `adoption-plain-app`: plain Java app with resources and a small test.
- `junit-basic`: JUnit Platform console runner.
- `junit-vintage`: JUnit Vintage test support.
- `spock-basic`: Groovy test sources and Spock.
- `workspace-app`: app/module/tools workspace with a configured task.
- `large-workspace`: larger workspace selection fixture.
- `spring-boot-webmvc`: Spring Boot web application.
- `spring-boot-petclinic-lite`: Spring Boot app with web, validation, data JPA,
  Thymeleaf, H2, resources, and tests.
- `spring-boot-enterprise-canary`: dependency policy, strict constraints,
  provided/dev scopes, annotation processors, OpenAPI generation, filtered
  resources, test runtime settings, WAR packaging, package metadata, and publish
  repositories.
- `quarkus-http`: Quarkus REST with fast-jar packaging.
- `micronaut-http`: Micronaut HTTP with annotation processors.
- `vertx-http` and `vertx-postgres-crud`: Vert.x application shapes.
- `protobuf-grpc-canary`: Protobuf/gRPC generated sources.
- `exec-jvm-canary`: exec step with `tool = "project"` generating a packaged
  build-info resource from the member's own classes (no external tool).
- `exec-process-canary`: `process` exec steps running committed `sh` scripts to
  generate a Java source and chain an intermediate into a packaged resource.
- `provided-container-api`: provided dependency/container API shape.
- `commons-cli-canary`, `hikaricp-canary`, `slf4j-canary`: library-style
  canaries and workspace/library packaging exercises.
- `migration-explain/*`: Maven and Gradle projects used by `zolt explain`.

## Platforms

Zolt ships only through the rolling `zap` nightly channel, which publishes native
binaries for:

- `linux-x64`
- `linux-arm64`
- `macos-arm64`
- `macos-x64`

Windows (`windows-x64`) is **experimental and not yet supported as a host**. CI
runs a non-blocking `windows-smoke` discovery lane that builds the workspace and
tests a couple of small modules from the bash bootstrap under Git Bash, and the
managed-toolchain catalog can lock a checksum-verified `windows-x64` JDK on
request — but no Windows native binary is produced or published, and rough edges
remain (for example, `zolt shims` emits POSIX shell scripts). Treat any Windows
use as unsupported until a native target ships.

## Good Fit

Zolt is strongest when you want:

- a project-owned Java build model in `zolt.toml`;
- deterministic dependency resolution in `zolt.lock`;
- fast local commands from one native binary;
- workspace-aware build/test/package flows;
- typed plans and JSON output for tooling;
- CI checks that understand the package and dependency model;
- migration analysis for Maven or Gradle inputs;
- native distribution and release verification for Zolt-produced artifacts.

It is still Java-focused and intentionally opinionated. For unusual build
plugins, custom Gradle logic, or framework behavior outside the implemented
lanes, use `zolt explain`, `zolt plan`, and the examples above to check the
current fit before assuming full drop-in replacement coverage.
