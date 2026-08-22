# Library Publishing (BOM + Workspace Families) — Design Record

Status: decided 2026-07-23. Implementation contract for BOM authoring and
publishing, dependencyManagement POM generation, classifier/type emission,
inter-member POM rendering, and `zolt publish --workspace`.

## Principle: zolt-native symmetry

The consuming vocabulary is `[versions]` + `[platforms]` + the deterministic
lock. Producing mirrors it: a BOM publishes a curated version set — workspace
members at locked versions plus chosen third-party pins — as the platform
other teams import via `[platforms]`. `[bom.versions]` mirrors `[versions]`,
`[bom.imports]` mirrors `[platforms]`, `[bom].members` is the family.

## Load-bearing facts (verified; the implementation relies on these)

1. `PublishPomGenerator` takes dependency versions from the LOCK
   (`direct() && publishedScope()`, `lockPackage.version()`).
2. No pom packaging exists; `PackageMode` lacks BOM; no depMgmt emission.
3. `DependencyMetadata` carries classifier/type (parsed) but the POM emit
   drops them — extend `PublishPomDependency` + emit (Maven element order:
   classifier after artifactId, type after version). Applies to jar POMs too.
4. Only the workspace ROOT has `zolt.lock` (aggregated); members have none.
5. Inter-member deps exist in the aggregated lock as `source="workspace"`
   packages with real GAV + the member's version.
6. **CRITICAL:** the aggregated lock's `direct` flag is OR'd across members —
   it must NEVER drive a member's POM. Directness comes from the member's
   own zolt.toml; versions come from the lock. A per-member projection
   (`WorkspaceMemberPomLockProjection`, zolt-workspace) builds a
   single-project-shaped lockfile: each config-declared direct coordinate
   (api/compile/runtime/provided + workspace deps; dev/test excluded) becomes
   a direct LockPackage at its aggregated-lock-resolved version. The
   generator then runs unchanged.
7. `PublishCentralBundle` is already one deterministic zip = Central's atomic
   deployment unit; `CentralPortalClient.upload` returns one deployment id.
8. `PublishCentralReadiness` unconditionally demands sources/javadoc — must
   branch on packaging (pom → those requirements OMITTED from the checklist;
   signing/checksums stay).
9. `PublishDryRunService` assumes one archive — plan gains a pom-only shape.
10. `PublishDryRunQualityCheck` hard-fails for workspace members today —
    replaced by a real workspace preflight.
11. explain already accepts pom packaging and extracts dependencyManagement
    (incl. import scope + external parents) — the migration hook reuses it.
12. CLI workspace selection mirrors BuildCommand verbatim
    (`--workspace/--all/--member/--members` → CommandWorkspaceSelections).
13. Layering: add zolt-workspace → zolt-publish (downward, safe); zolt-publish
    must never import zolt-workspace.

## BOM authoring

A BOM is a dedicated workspace member (rejected: root-level `[bom]` — the
workspace root has no `[project]` and cannot own a published GAV identity,
and platform teams ship multiple BOMs; `members = true` recovers root's
zero-config ergonomics). Standalone non-workspace BOMs work too (no members).

```toml
[project]
name = "platform-bom"
group = "com.acme.platform"
version = "1.4.0"

[bom]                      # any authored BOM domain is the BOM signal
members = true             # or explicit member path list; exclude = [...] supported

[bom.versions]             # mirrors [versions]: fixed literals or versionRef
"org.postgresql:postgresql" = "42.7.4"
"io.netty:netty-transport-native-epoll" = { versionRef = "netty", classifier = "linux-x86_64" }

[bom.imports]              # mirrors [platforms]: emitted type=pom scope=import
"com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }

# Central metadata lives in [project], [project.scm], and
# [project.developers.<id>] like any other publishable project.
```

Mode rules: any authored `[bom]`, `[bom.versions]`, or `[bom.imports]` implies
`PackageMode.BOM("bom")`; authored `mode = "bom"` is rejected because the BOM
domain is the sole canonical signal; any other mode alongside `[bom]` is a config
error, as are any dependency lane on a BOM member, an authored `project.java`,
and sources/javadoc/tests/manifest/duplicates under BOM.
Build/package semantics: no compile, no jar; the artifact IS the generated
POM (`target/publish/<name>-<version>.pom`, recorded in package evidence with
sha256). run/run-package error actionably. Members=true resolves from the
enclosing workspace's member graph; member versions come from the aggregated
lock's workspace packages (fact 5), never re-read from config.

## POM shapes

BOM: `<packaging>pom</packaging>`; NO `<dependencies>`;
`<dependencyManagement><dependencies>` sorted by group:artifact:classifier:
members (GAV at locked version), `[bom.versions]` pins (+ classifier/type),
`[bom.imports]` as `<type>pom</type><scope>import</scope>`. All versions
fixed literals (ranges/dynamic/${} already parse-rejected). Golden fixtures:
bom-family.pom.xml, inter-member.pom.xml, classifier.pom.xml (byte-equality).
Inter-member completeness: publishing a member whose sibling dependency is
not in the publish set fails Phase 1 naming the pair (single-member publish
of a workspace member renders correctly but warns the sibling must be
published at the same version). Never silently publish a POM referencing a
coordinate consumers cannot resolve.

## `zolt publish --workspace`

Orchestrator `WorkspacePublishService` in zolt-workspace (sh.zolt.workspace
.publish), BuildCommand-style selection, fresh-lock guard, BOM assembled last.
**Phase 1 (offline):** per member — policy-resolved config, lock projection,
POM generation + validation, package plan, readiness (Central), inter-member
completeness; aggregate ALL blockers into one family report; any blocker →
nothing uploads. **Phase 2:** Central → ONE family bundle (all members + BOM,
every file + checksums + .asc, one deterministic zip, one deployment id =
atomic release; rejected per-member sequential bundles: N failure points, no
atomicity, N× validation latency, --wait multi-id juggling). Plain repo →
dependency-ordered sequential PUT (provider before consumer, BOM last),
fail-fast with the exact resume command (`--members <remaining>`).
**Versions:** uniform family version enforced by default (divergence fails
Phase 1, listing offenders); `--allow-mixed-versions` opts out (BOM then pins
each member at its own version).

## Interplay

`--sbom`: per-member SBOM attachments (each consumed jar gets its own);
the BOM artifact gets none (no resolved graph — an SBOM would be misleading).
`zolt sbom` on a bom member: minimal BOM-metadata-only CycloneDX + a stderr
note, never an error. `--wait`: Central family = one deployment id, existing
single-poll semantics; rejected for plain-repo workspace publishes.
`zolt check --require-publish-dry-run --workspace`: replaces today's
hard-fail with the Phase-1 family preflight as one CI gate (members without
[publish] are skipped unless they are inter-member targets of the set — then
their absence is a blocker).

## Migration hook

Signal `maven.bom.detected` (OK/BUILDABILITY): pom-packaging + non-empty
dependencyManagement + no modules + no sources. `--emit-toml` drafts the
`[bom]` member from MavenManagedVersions' already-extracted data: import
scope → `[bom.imports]`, plain pins → `[bom.versions]`, reactor siblings →
members. Parent-POM depMgmt seeds the drafted pins.

## Canary (the trust piece)

examples/platform-family (acme-core, acme-http depending on it, acme-bom
members=true, uniform 1.0.0) + examples/platform-family-consumer
([platforms] imports acme-bom; acme-http declared `{ managed = true }`).
PublishWorkspaceBomCanaryTest: publish --workspace into a @TempDir file repo
(plain PUT, unsigned) → consumer resolves → asserts: resolution succeeds from a
platform-managed declaration; versions 1.0.0 arrive via the BOM (platform-managed
provenance, not direct pins); consumer lock records the fixture repo source;
published acme-bom POM byte-equals the golden; acme-http POM carries the
inter-member dependency on acme-core@1.0.0.

## Refusals

Gradle Module Metadata production (POMs are the cross-tool contract; Zolt
itself doesn't read GMM). Maven `<parent>` production (composition over
inheritance; import the BOM instead). Ranges/dynamic in depMgmt. Dependencies
in a BOM. Per-member Central deployments for a family. BOM GAV inference.

## Stages

1 (M) POM engine: PackageMode.BOM, BomSettings + BomSectionCodec (implied
mode), WorkspaceMemberPomLockProjection, depMgmt + classifier/type emission,
inter-member guard; golden POM tests + projection tests (directness from
config, versions from lock). 2 (S–M) readiness packaging branch, pom-only
dry-run plan, BOM build/package evidence. 3 (M) WorkspacePublishService:
two-phase, family bundle, ordered sequential + resume, uniform-version check,
PublishCommand flags. 4 (S) check --require-publish-dry-run workspace path,
per-member --sbom, sbom-on-bom. 5 (S) migration signal + [bom] draft.
6 (S) canary examples + harness + USAGE. Registration checklist every stage.

## Must-not-do

1. Never a non-fixed version in dependencyManagement — pinning is the BOM's
   entire job. 2. Never a partial family upload — Central atomic bundle;
   plain repos ordered + fail-fast + resume. 3. Never derive a member's POM
   directness from the aggregated lock's OR'd `direct` flag (fact 6) —
   config for directness, lock for versions.
