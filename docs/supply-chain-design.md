# Supply Chain (SBOM + Licenses) — Design Record

Status: decided 2026-07-22. Implementation contract for `zolt sbom`,
`zolt licenses`, the license policy gate, and publish attachment.

Implemented scoped exceptions and explicit SPDX-expression semantics are specified
in [`docs/license-policy-exceptions-design.md`](license-policy-exceptions-design.md).

## Load-bearing fact

`zolt.lock` ALREADY stores the dependency graph edges: each `[[package]]`
carries `dependencies = ["g:a:v", ...]` written by
`LockfileAssembler.dependenciesFor` from the resolve graph, and `zolt tree` /
`zolt why` already reconstruct the full graph offline from exactly this field
as component-graph views. They intentionally flatten path-specific exclusion
contexts: an edge shown for a component says that the component has that
relationship in at least one locked path, not that every path to the component
has the relationship. Optional-boundary propagation is therefore computed from
resolver reachability facts captured while cumulative exclusion contexts are
still present, before component edges are flattened. Each `[[memberGraph]]`
persists `declaredOptional` separately from effective `optionalOnly`
reachability. A directly optional package can therefore remain required through
another root without corrupting its published optional metadata. Workspace
classpath and per-member SBOM consumers use `optionalOnly` to prevent an edge
that exists only on an optional path from crossing a required workspace-member
boundary. The SBOM
projection removes the filtered edge as well as the optional-only target, so it
cannot emit a dangling relationship. Member-qualified component graph contexts
remain available to workspace SBOM and classpath consumers that require
member-sensitive projection (defensive lookup, cycle-guarded). Commands that
consume this workspace graph require lock version 5; versions 1–4 remain
readable only for operations that do not need optional-boundary evidence.
Therefore the SBOM graph is a READ of the lock — no re-resolution (rejected:
needs warm cache/network, drags zolt-resolve in, re-derives persisted data) and
no parallel edge table (rejected: it duplicates existing data for zero new
capability). Hashes
(jarSha256/artifactSha256) and per-member attribution (`members`) are also
already in the lock.

## Module placement

New workspace member `modules/zolt-sbom` (namespace sh.zolt.sbom), depending
on zolt-model + zolt-toml + zolt-repository. NOT zolt-policy (stays lean,
config-only) and NOT via zolt-resolve (EffectivePomMetadataLoader is
network-capable and doesn't carry licenses; a ~30-line cache-only parent walk
is leaner and makes offline structural). zolt-quality gains a dep on zolt-sbom
for the policy check; apps/zolt gains it for the commands.

## Determinism contract

- CycloneDX spec 1.5 (plain metadata.tools array; universally consumed by
  Dependency-Track/Grype/Trivy). Subset versioned in code: bomFormat,
  specVersion, serialNumber, version, metadata{timestamp?, tools[],
  component}, components[]{type, bom-ref, group, name, version, purl, scope,
  hashes[], licenses[]}, dependencies[]{ref, dependsOn[]}.
- serialNumber = urn:uuid:UUIDv5(fixed namespace UUID, seed), seed = the
  lock's projectResolutionFingerprint (fallback: root coordinate + sorted
  purls). Identical inputs → identical serial. Never random.
- metadata.timestamp OMITTED by default. Resolution: --timestamp <iso8601> →
  value; else SOURCE_DATE_EPOCH → that instant; else --timestamp now →
  wall clock (explicit opt-in); else omitted.
- Every collection sorted before emission (components by purl, dependsOn
  sorted, hashes fixed order, licenses by id/name). No hash-order iteration
  reaches output. Golden-file byte-equality tests enforce all of this.
- Hand-rolled JSON writer in sh.zolt.sbom.json (copy of the zolt-tree
  DependencyJsonFields pattern; it is package-private there). No external
  libraries.

## `zolt sbom`

`zolt sbom [--format cyclonedx] [--output <path>] [--workspace] [--offline]
[--include-test] [--include-provided] [--include-tools] [--include-dev]
[--timestamp ...]`. Missing zolt.lock → actionable "run zolt resolve".
Scope model: COMPILE/RUNTIME included by default as scope "required";
PROVIDED/DEV (--include-provided/--include-dev), TEST/TEST_PROCESSOR
(--include-test), PROCESSOR/TOOL_*/QUARKUS_DEPLOYMENT (--include-tools) as
"optional". Multi-scope packages dedup to one component (required wins);
edges filtered to surviving endpoints. purl = pkg:maven/g/a@v?type=<ext>
[&classifier=<c>] (classifier best-effort from artifact filename;
percent-encoded); bom-ref = purl. Root component from [project] +
[package.metadata] (license from config — authoritative, not POM-extracted);
type application when packaged as an app. metadata.tools = zolt + version.
Workspace = ONE BOM (per-member rejected: procurement consumes one artifact;
the graph expresses boundaries): root workspace component, members as library
components, external components deduped with member→external edges from the
lock's members field and external→external edges from dependencies.
Distinct workspace and repository targets may not coexist at the same Maven
package, version, and artifact variant, regardless of scope: they collapse to
one CycloneDX PURL and would mix mutable local output with repository hashes.
Repository IDs are provenance rather than byte identity: external mirrors are
equivalent only when both the primary artifact SHA-256 and POM SHA-256 are
present and equal. Workspace aggregation then records the lexicographically
smallest repository source on every scope copy, so member order cannot change
the aggregate lock. Missing or different hashes remain a hard resolution
failure; this equivalence never applies to mutable workspace outputs.

## License engine (cache-only, no network — ever)

Additive zolt-repository change: RawPom gains List<RawPomLicense>
(name/url/distribution/comments), parsed by RawPomParser with the existing
hardened DOM helpers. PomLicenseResolver (zolt-sbom): read cached POM for each
in-scope package; if no <licenses>, walk <parent> coordinates through the
cache (nearest-ancestor wins, cycle-guarded); missing cached POM or empty
chain → UNKNOWN + one stderr warning suggesting `zolt resolve` (never fetch,
never fail the SBOM). Normalization via curated SpdxLicenseMapping keyed on
lowercased name AND url (Apache-2.0/MIT/BSD-2/BSD-3/EPL-1/EPL-2/LGPL/GPL
(+classpath)/CDDL/MPL-2.0/ISC/Unlicense/EDL spellings). A license name that is
itself an SPDX identifier or expression is parsed first against the source-pinned
3.28.0 catalog. Status per license: SPDX (→ {"license":{"id"}}),
SPDX_EXPRESSION, UNMAPPED (raw name+url kept, flagged — never guessed into a
nearby id), UNKNOWN (component licenses omitted in SBOM, surfaced in
reports/policy). Multiple Maven license records remain discrete alternatives;
Zolt never invents an expression because Maven semantics are ambiguous. No
persisted memoization (POMs are disk-cached; extraction is pure); in-process
per-run memo only.

## `zolt licenses`

Text report grouped by license with per-dependency attribution and actionable
notes for UNKNOWN/UNMAPPED; `--format json` (Zolt-native schemaVersion 1
groups[] view); same scope flags as sbom. `--notices <path>` writes a
deterministic THIRD_PARTY notices file (coordinate — license (url) +
POM-derived attribution). Full license TEXT is NOT bundled in v1 (offline
guarantee + maintenance; v2 path: curated texts for top permissive ids).

## License policy

Config home `[dependencyPolicy.licenses]`: `allow = [...]`, `deny = [...]`,
`unknown = "fail"|"warn"|"allow"` (default warn — failing on UNKNOWN by
default would break most real projects; strict shops set fail in CI). Exact
`[dependencyPolicy.licenses.exceptions."group:artifact"]` tables extend a
non-empty allow list with canonical SPDX terms and require a review reason;
an optional `version` is exact. Missing, mismatched, and redundant exceptions
fail the quality gate, and no exception can override global deny.
Precedence rule (one sentence, no ambiguity): permitted iff id ∉ deny AND
(allow empty OR id ∈ allow) — deny always wins; a non-empty allow-list is
authoritative. UNMAPPED matches by its raw string, otherwise follows the
unknown strictness. Model: DependencyPolicySettings gains
LicensePolicySettings (additive, back-compat overload). Codec: nested table
under the existing dependencyPolicy section with key validation. Enforcement:
new `zolt check` id `license-policy` (in IMPLEMENTED_CHECKS +
CI_CONTEXT_CHECKS), LicensePolicyQualityCheck in zolt-quality delegating to
zolt-sbom's evaluator; offline; failures name dependency, license, and the
policy line with a Next: (remove dep / policy exclude / amend policy).
Reporting: `zolt licenses` annotates the same evaluator's verdicts onto its own
output — `[denied]`/`[unknown]`/`[exception]` markers carrying the evaluator's
reason, exception lifecycle audits, a counts summary, and a pointer at the
enforcing command — through
`LicensePolicyAnnotations` in zolt-sbom, which both writers accept as an optional
argument. Enforcement does not move: `zolt licenses` still exits 0, and with no
policy configured its text and JSON output are byte-unchanged (JSON gains new
fields only). Annotation is scoped the way enforcement is, on both axes.
Scope axis: the report lists every `--include-*` scope the user asked for, but
annotations come from a separate `SbomScopeSelection.requiredOnly()` assembly —
the same selection `LicensePolicyQualityCheck` evaluates — so an optional-scope
entry is listed unannotated rather than marked against a policy the named command
never applies to it. A coordinate in an enforced and an optional scope is in that
assembly (`LockSbomAssembler` merges multi-scope duplicates, required wins) and is
annotated. Widening `zolt check --check license-policy` is a separate decision;
until it is taken, annotation follows it rather than leading it. Member axis: in a
workspace `[dependencyPolicy]` is member-local, so the CLI pairs each member's
effective config with that member's projected external closure — the same
`WorkspaceMemberSbomLockProjection` the workspace quality check consumes, filtered
at `requiredOnly()` whatever the report selected — and passes those
`LicensePolicyScope` pairs into the evaluator. A coordinate takes the strictest
verdict among the members that consume it, and a member that does not consume it
contributes nothing, so `zolt licenses --workspace` and `zolt check --workspace
--check license-policy` cannot disagree. Pairing in the CLI is deliberate:
zolt-sbom gains no dependency on zolt-workspace, only the projected component list
crosses the boundary. The `evaluated` denominator counts distinct coordinates in
the enforcing closure, matching `LicenseReportBuilder`'s per-coordinate dedup
rather than the aggregate component-entry count.

Workspace quality checks consume one shared member projection rather than the
aggregate lock. The projection merges workspace-root repositories/platforms
through `WorkspaceMemberPolicyResolver`, retains each member's all-scope
package/variant/conflict/policy graph, and separately supplies the existing
per-member SBOM closure. It also plans `package-contents` from the exact
per-member package/runtime closure used by workspace packaging, including
workspace outputs and BOM POM plans; it never reconstructs a member package
from every JAR in the aggregate lock. License policy evaluates external components only;
first-party workspace packages never become unknown third-party findings.
Dependency metadata matches member + package + variant + scope, validates
optional workspace declarations against both the project edge and version-5
member graph, and parses qualified dependency edges for exclusions. Explicit
dependency-metadata, dependency-policy, license-policy, and package-contents
checks all require version-5 graph capability even when `lockfile` was not
requested. A root-only `members = ["."]` workspace follows the same aggregation
contract: its project fingerprints remain unchanged while external packages,
exports, and optional facts gain `"."` member attribution.

## Publish attachment (flag-gated, off by default)

`zolt publish --sbom` adds a supplemental artifact with classifier
`cyclonedx`, extension `json` (`<artifact>-<version>-cyclonedx.json` — the
Central/CycloneDX-Maven-plugin convention; `.cdx.json` is a file-suffix
convention, not the Maven classifier form Central indexes). Rides the existing
supplemental planner: checksums + signing come free; bundle stays
byte-reproducible.

## Refusals

No vulnerability scanning (CI-side, owner-ratified). No license text bundling
v1. No network at sbom/licenses time. No SPDX document format v1 (CycloneDX
satisfies procurement; SPDX is a v2 --format on the same SbomModel).

## Stages

1 (S): zolt-sbom module + `zolt sbom` lock-only (components/hashes/edges/
purls/root/tools, no licenses). 2 (M): RawPom licenses + PomLicenseResolver +
SpdxLicenseMapping + licenses into SBOM + `zolt licenses` + --notices.
3 (S): policy model/codec/evaluator + license-policy check. 4 (S): workspace
one-BOM aggregation + publish --sbom. Registration checklist every stage:
ZoltCli, reflect-config (CliNativeReflectionConfigTest enforces), help-surface
fixture. Tests: golden-file byte-equality (incl. SOURCE_DATE_EPOCH invariance
and serial stability/sensitivity), POM fixtures (direct, parent-inherited,
dual, unmapped, absent), policy precedence + unknown strictness matrix, scope
dedup/edge filtering, workspace aggregation.

## Must-not-do

1. Non-determinism reaching the bytes (random serial, default wall-clock
   timestamp, hash-order iteration). 2. Network at sbom/licenses time — a
   missing POM is UNKNOWN, never a fetch. 3. License coercion or UNKNOWN
   hard-fail: unmapped stays raw and flagged; UNKNOWN is reported and
   policy-gated but never aborts SBOM generation.
