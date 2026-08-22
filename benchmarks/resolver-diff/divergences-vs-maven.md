# Dependency resolution: how Zolt differs from Maven

Reference note (2026-07-10) that can be lifted into the docs site. Every
claim below is pinned by code + tests in this repo, and the empirical
claims by the `resolver-diff` sweep. Zolt's divergences are deliberate
narrowings in service of reproducibility; this page makes them a documented
contract.

## Version conflict mediation: direct-preference + newest-wins

When the graph requests multiple versions of the same `group:artifact`,
Zolt picks one winner:

1. If any **direct** dependency (declared in your `zolt.toml`) requests the
   package, the newest directly-requested version wins.
2. Otherwise the **newest** requested version wins.

Maven instead picks the version **nearest the root** of the tree (ties by
declaration order). Consequences:

- Zolt never silently downgrades a transitive below what some dependency
  asked for; Maven routinely does (nearest wins even when older).
- To force a version down, declare it directly or use
  `[dependencies.constraints]` — mirroring Cargo/Gradle habits, not Maven's
  "add a nearer dep" trick.
- Every mediation is recorded in `zolt.lock` as a `[[conflict]]` entry with
  the requested set and the reason (`direct dependency wins` /
  `newest version wins`), and `[dependencies.policy].conflicts = "fail"`
  can hard-fail instead.

Observed in practice (25-root differential sweep): spring-boot-starter-web
resolves slf4j-api 2.0.16 where Maven pins 2.0.15; okhttp resolves
kotlin-stdlib 1.9.10 where Maven keeps 1.8.21.

## Rejected instead of resolved

Zolt refuses, with an actionable error, inputs Maven resolves dynamically:

- **Version ranges** `[1.0,2.0)` — use a fixed released version.
- **Dynamic versions** — `+`, `latest`, `latest.release`,
  `latest.integration`, `release`.
- **External `-SNAPSHOT` versions** — allowed only for your own project
  version and snapshot publishing, never for (transitive) dependencies.
- **Unsupported POM scopes** — only `compile`, `runtime`, `test`,
  `provided` are accepted; `system` is rejected.
- **Uninterpolated versions** (`${...}`) at point of use.

There is **no `maven-metadata.xml` handling at all**: no snapshot
timestamp/buildNumber resolution and no `latest`/`release` lookup.
Artifact paths derive purely from coordinates.

## dependencyManagement and BOMs

- Parent-chain dependencyManagement is inherited nearest-POM-wins;
  `<scope>import</scope>` BOMs are expanded in place, with import-cycle
  detection.
- A dependency's explicitly declared version is never overridden by
  dependencyManagement (same as Maven).
- Project-level `[platforms]` BOMs and `[dependencies.constraints]` apply
  before any POM-level management, in that order.
- Known micro-divergence: within a single POM's dependencyManagement,
  duplicate entries resolve **last-declared-wins** (Maven:
  first-declared-wins).

## Scopes

POM scopes map into Zolt's richer scope vocabulary (`compile`, `runtime`,
`dev`, `test`, `provided`, plus processor/tool scopes). Transitive scope
derivation matches Maven's shape: `test`/`provided` dependencies never
travel; a `runtime` parent forces its children to `runtime`. A package may
legitimately appear in `zolt.lock` under **multiple scopes**; Maven
flattens to a single effective scope.

## Exclusions and optionals

- POM `<exclusions>` support Maven wildcards (`*:*`, `group:*`).
- `[dependencies.policy].deny` in `zolt.toml` deliberately rejects
  wildcards — project-level exclusions must be explicit, and can carry a
  `reason`.
- Transitive `optional` dependencies are skipped, exactly like Maven.

## Repositories and integrity

- Repositories are tried in **alphabetical order of their id**, first
  repository that has the artifact wins — not declaration order (TOML
  tables are unordered), and there is no Maven mirror concept.
- Integrity is lockfile-pinned: SHA-256 of every jar/pom is recorded at
  first fetch and verified from the local cache on every build. Repository
  `.sha1`/`.sha256` sidecars are not consulted; first fetch trusts the
  TLS channel (trust-on-first-use, like Cargo).

## Prerelease ordering

Version ordering follows Maven's generation rules with one nano-divergence:
Zolt ranks `-SNAPSHOT` below `alpha` (lowest), Maven ranks it just below
the release. This only affects ordering among prereleases and cannot be
observed for external dependencies (SNAPSHOTs are rejected there anyway).

## Empirical status

The `resolver-diff` harness resolves real Maven Central roots both ways and
classifies every divergence. Baseline sweep (2026-07-10, 25 roots):
21 identical to Maven, 5 divergences all confirmed as intended
newest-wins mediation, plus two defects found by the sweep: parent
`<dependencies>` inheritance and eager interpolation of unused
dependencyManagement entries. The defects are covered by targeted
regression tests on the fix branch; rerun the networked harness before
updating the baseline result artifacts.
