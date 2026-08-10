# Dependency Updates — Design Record

Status: decided 2026-07-22, amended 2026-08-10. Implementation contract for
`zolt outdated`, policy and exact `zolt update`, plus the external Renovate
manager spec.

## Command naming

The top-level `zolt update` (today a redundant alias of `zolt self update` for
binary self-update) is REPURPOSED for dependency updates. Binary self-update
stays fully reachable at the canonical `zolt self update` (the self-update
notice already points there). The old alias command class is deleted;
`RootCommandListRenderer` moves `update` into the Dependencies group and adds
`outdated`; `ZoltToolchainNoticeHook`'s update suppression re-keys to the self
path; reflect-config and the help-surface fixture update accordingly.

## Boundary contract (append to USAGE "Resolution and Lockfile Contracts")

Version discovery is advisory-only. `maven-metadata.xml` is fetched ONLY by
`zolt outdated` and policy-driven `zolt update`. Exact target update does not
query metadata. Resolution (`zolt resolve`, `--locked`,
build/test/package, workspace resolve, `explain verify`) never fetches, reads,
or is influenced by version listings, and `zolt.lock` never records one. A
discovered version enters a build only when written as a fixed literal into
`zolt.toml` and re-resolved. Enforced structurally (no `zolt-resolve` →
discovery dependency) and by a test asserting a resolve issues zero
`maven-metadata.xml` requests.

## Metadata cache policy (TTL rejected)

Listings are mutable and never touch the immutable artifact cache. Separate
namespace `~/.zolt/cache/metadata/<repoId>/<groupPath>/<artifactId>/
maven-metadata.xml` + `.fetched` timestamp sidecar, atomic writes. Online:
always refetch; on transient failure fall back to cache WITH a staleness note.
`--offline`: cache only; missing listing → status unknown with note. TTL was
steelmanned (bandwidth, Maven familiarity) and rejected: a TTL silently serves
a wrong "up to date" inside the window; fallback-only-on-failure is always
annotated. Conditional GET is a future optimization that preserves
always-refresh semantics.

## Discovery layer (modules/zolt-repository, new package sh.zolt.maven.metadata)

`MavenRepositoryPathBuilder.metadataPath(group, artifact)`;
`MavenRepositoryClient.fetchMetadata(...)` → `Optional<byte[]>` (404 → empty,
retries per existing policy); `MavenMetadata` record (versions list; ignore
latest/release hints); `MavenMetadataParser` (hardened XML config mirroring
RawPomParser); `MetadataCache`; `RepositoryMetadataService` orchestrator.
Repository list + auth + order come from the SAME planner resolve uses —
promote/extract `RepositoryAccessPlanner` so discovery and resolve share one
implementation (alphabetical-by-id order preserved). Listings are UNIONED
across repos (dedup by exact version string; a candidate's `source` = first
repo in query order listing that version). First-found-only was rejected: it
hides newer versions that live only in a lower-priority repo. Missing metadata
degrades to status unknown, never an error.

## Classification (modules/zolt-model, beside VersionComparator)

Reuse `VersionComparator` verbatim for ordering. New: `UpdateClass`
{PATCH, MINOR, MAJOR}; `VersionStability` {RELEASE, PRERELEASE, SNAPSHOT} —
SNAPSHOT always excluded regardless of flags; PRERELEASE iff a KNOWN
negative-rank qualifier token {snapshot, alpha, a, beta, b, milestone, m, rc,
cr}; **unknown qualifiers are RELEASE** (load-bearing: keeps `33.4.8-jre`,
`-android`, calver eligible). `VersionClassifier`: releaseCore = leading
numeric tokens {major, minor, patch}; candidates(current, listing,
includePrereleases) → latestPatch (same major.minor), latestMinor (same
major), latestMajor (overall), selectedInMajor (update default target),
selectedLatest (--latest target). A prerelease current sees same-core GA as
PATCH.

## `zolt outdated` (new module modules/zolt-update, thin CLI command)

Surfaces enumerated: `[versions]` aliases (primary lever; `governs` lists
referencing coordinates), literal-versioned deps in every scope (skip
versionRef entries — they report under their alias), `[platforms]` (managed
deps report under the PLATFORM, never per-dep; effective versions shown from
lock), `[annotationProcessors]`/test, `[dependencyConstraints]`,
`[generated.execTools]` jvm coordinates + protobuf/openapi tool refs. Ignored:
`[toolchain.*]` (separate lifecycle), workspace-member deps, SNAPSHOT
literals. Flags: `--format text|json`, `--include-prereleases`, `--all`,
`--offline`, selectors (coordinate | alias | section token). Workspace: per-
member blocks + root, shared-coordinate dedup note. JSON schema v1: stable
keys, nulls not omission, `surface` ∈ {versionAlias, dependency, platform,
annotationProcessor, dependencyConstraint, execToolCoordinate, protobufTool,
openapiTool}, `status` ∈ {current, update-available, unknown}, candidates by
class + selectedInMajor/selectedLatest + source repo + governs + members.
Deterministic ordering (aliases first, then group:artifact sort).

## `zolt update`

Default = latest STABLE within current MAJOR. `--latest` allows majors;
`--patch|--minor|--major` set the ceiling; `--include-prereleases` widens
(never SNAPSHOT); selectors scope. `--dry-run` prints a SEMANTIC edit list
(the source-preserving TOML writer patches only modeled declarations) with
from → to, class, and fan-out counts; `--format json` emits edits[]/skipped[].
Apply path uses ONLY existing mutation machinery: aliases via
withVersionAliases (the primary lever; ALWAYS warn with the full fan-out list
— extend VersionAliasCommands.references to also scan execTool/protobuf
versionRefs, which it misses today); literal deps via
ProjectConfigDependencyMutator.addDependency in their actual section
(metadata — exclusions/classifier/type/optional — is preserved by the
retained-metadata path); platforms via addPlatform; constraints rebuilt
preserving kind/reason. A versionRef-backed coordinate NEVER gets a literal
written — its alias updates instead. Literal execTool coordinate mutation is
stage 3; until then update reports them as skipped. Comment-rewrite warning
preserved. After a successful write, resolve runs BY DEFAULT (`--no-resolve`
opts out; `--dry-run` does neither) — matches every sibling mutation command
and keeps zolt.lock consistent; the steelman (bulk-bump review, CI staging)
loses because the edit is already saved and `--dry-run` is the review valve.
`update` has NO zolt.lock read/write path of its own.

## Exact target automation amendment

Schema v1 remains the default for both `outdated` and policy-driven `update`.
`zolt outdated --format json --schema-version 2` adds canonical mutation-root-
relative manifest/lock paths and an opaque SHA-256-derived `zt1_` ID for every
reportable surface. Identity includes only schema version, manifest path,
surface, section, and identifier. Current/candidate versions, fan-out,
updateability, and lockfile state are descriptive and excluded from identity.

`zolt update --target-id ID --to VERSION` selects exactly one ID. Exact mode is
incompatible with selectors, ceilings, and `--offline`, but retains dry-run,
no-resolve, and prerelease opt-in. It validates through `VersionPolicy`, rejects
downgrades and comparator-equivalent rewrites, and never performs metadata
discovery. Availability is proved by the existing staged resolve transaction.

Workspace lookup catalogs all declared members under one confirmed root lock.
Execution reacquires that lock, verifies the expected manifest/root-lock paths,
rebuilds the target from the current manifest, and replans before applying via
the same `UpdateApplier` as policy mode. A removed/moved target or changed scope
fails closed; an already-applied destination becomes a no-op. Transaction output
reports actual manifest and lockfile byte changes in deterministic order.

Exact JSON requires schema v2 and reports `changed`, `applied`, `resolved`,
`changedFiles`, alias fan-out, and structured diagnostics. Valid machine failures
retain that schema number. Policy JSON stays schema v1.

## Renovate manager spec (appendix — external repo, not built here)

File match `(^|/)zolt\.toml$`; datasource=maven; registryUrls from
`[repositories]`. Shapes: inline `"g:a" = "v"` in all dependency scopes;
inline table `{ version = "v", ... }`; versionRef indirection → the updatable
unit is `[versions].<name>`; `[platforms]` (packaging=pom);
`[dependencyConstraints]`; execTools coordinate arrays + protobuf/openapi
tool refs through `[versions]`. Must never touch `[toolchain.*]`, workspace
paths, SNAPSHOTs; must only write fixed literals. PREFERRED bridge:
`zolt outdated --format json` as a custom datasource — Zolt's own comparator
and stability rules are the single source of truth; regex shapes are the
fallback for shops that won't run zolt in the bot.

## Refusals

No auto-PR creation, no scheduling, no toolchain JDK updates, no ranges ever
written, no SNAPSHOT suggestions, never edits zolt.lock, never fetches
metadata during resolve/build.

## Stages

1 (M): discovery layer + classifier + `zolt outdated` (all surfaces, JSON +
table, workspace, offline/fallback). 2 (M): `zolt update` (plan/apply engine,
semantic dry-run, default-resolve, fan-out warnings, command repurpose).
3 (S, later): literal execTool mutator + conditional GET.
Tests: metadata fixtures on the local HTTP harness (union/order/404/fallback/
offline), classifier units (jre-flavor, rc→GA, calver, prerelease widening),
parser hardening, apply round-trips (metadata preservation, alias-not-literal,
dry-run writes nothing), the zero-metadata-requests resolve boundary test,
CLI surface tests + reflect-config/help-fixture updates.

## Must-not-do

1. Metadata leaking into resolve or the lock (module boundary + boundary
   test). 2. Metadata stored in or served through the immutable artifact
   cache. 3. Update corrupting intent: dropped dependency metadata, literal
   written over a versionRef, suppressed comment warning, or any range.
