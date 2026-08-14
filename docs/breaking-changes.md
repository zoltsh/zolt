# Breaking Changes

Breaking changes to Zolt's CLI and configuration, newest first. Each entry names
the old behavior, the new behavior, and how to migrate.

## Lockfile version 6 binds content-addressed artifact cache paths

- **Old behavior:** version-5 locks could name Maven-layout cache paths such as
  `org/example/lib/1.0/lib-1.0.jar`, while newly generated version-5 locks named
  content-addressed `blobs/v2/sha256/...` paths. A cold cache exposed the two
  incompatible meanings only after a locked resolve had already downloaded the
  artifacts and failed the lock comparison.
- **New behavior:** newly resolved locks use version 6, which explicitly means
  every repository artifact path is content-addressed. Versions 1 through 5
  remain readable for compatible metadata and diagnostics, but commands refuse
  to materialize their legacy cache paths and report the migration command
  before cache or network work.
- **Migration:** run `zolt resolve` for a project lock or
  `zolt resolve --workspace` for a workspace lock, then commit the regenerated
  version 6 `zolt.lock` before building, testing, packaging, or using
  `--locked`.

## Workspace tree schema 3 binds member identities and graph roots

- **Old behavior:** `zolt tree --workspace --format json` emitted schema 2 with
  member paths but no exact path-to-package identity mapping.
- **New behavior:** schema 3 emits each member's path, Maven identity, and exact
  locked graph roots, and marks every first-party package occurrence
  with its owning member path.
- **Migration:** update machine consumers to accept schema 3. Standalone tree
  output remains schema 1.

## Lockfile version 5 preserves optional boundaries and conflict provenance

- **Old behavior:** workspace optional dependencies could cross into downstream
  member classpaths, a whole-workspace SBOM flattened member-qualified graphs,
  rematerialization could replace the original member conflict evidence, a
  root-only `members = ["."]` workspace preserved an unqualified project lock,
  and `package-contents` planned every member from the aggregate lock.
- **New behavior:** newly resolved locks use version 5. Member graph facts carry
  direct `declaredOptional` separately from effective `optionalOnly`
  reachability captured before path-specific exclusion contexts are flattened,
  `[[conflict]]` entries carry member attribution, and identities
  with any member-qualified graph facts must cover every attributed member.
  Whole-workspace SBOMs create distinct graph `bom-ref` contexts when members
  share a PURL but have different outgoing edges. Workspace resolution also
  rejects mixed local and repository targets at the same package, version, and
  variant even when they occur in different scopes; scope cannot distinguish
  two byte identities in Maven or CycloneDX. Workspace graph-consuming commands
  refuse readable pre-v5 locks rather than treating missing optional facts as
  required, including explicitly requested dependency metadata, dependency
  policy, and license policy checks. `zolt check --workspace` evaluates each
  member's effective root-merged policy and exact variant/scope graph; license
  checks exclude first-party workspace packages. Per-member published SBOMs
  apply optional-only facts while traversing sibling graphs and remove both the
  omitted component and its edge. Root-only workspaces are aggregated normally,
  retaining the project fingerprints while adding `members = ["."]`, exact
  `exportedBy`, and `[[memberGraph]]` evidence. Version-5 workspace locks with
  unqualified external packages are rejected and must be re-resolved.
  `package-contents` is also graph-dependent and consumes the same exact
  per-member package/runtime closure as workspace packaging; sibling
  dependencies and policy effects stay isolated, optional provider closures do
  not leak, and BOM members use a POM package plan instead of a JAR layout.
  Finally, ordinary workspace dependencies may target only `thin` members:
  executable, Quarkus, uber, WAR, and BOM members are application artifacts,
  not reusable library JARs.
- **Migration:** run `zolt resolve` for a project lock or
  `zolt resolve --workspace` for a workspace lock, then commit the regenerated
  version 5 `zolt.lock`. Make every affected consumer use the workspace member
  or give the local project a distinct version. Split shared code needed from an
  application-packaged member into a separate `thin` workspace member. Existing
  root-only version-5 locks must also be regenerated to add member attribution.

## Lockfile version 4 preserves member-qualified workspace graphs

- **Old behavior:** workspace aggregation collapsed identical external package
  identities onto one member's dependency and policy graph. Different member
  exclusions could therefore become order-dependent, even when the artifact and
  POM bytes were identical.
- **New behavior:** newly resolved locks use version 4. When member graph facts
  differ, `[[memberGraph]]` entries preserve each member's variant- and
  scope-qualified dependencies and policies. Workspace classpath and SBOM
  projections consume that evidence. Aggregation also refuses different
  artifact or POM hashes for the same selected identity.
- **Migration:** run `zolt resolve` for a project lock or
  `zolt resolve --workspace` for a workspace lock, then commit the regenerated
  version 4 `zolt.lock`.

## Lockfile version 3 scope-qualifies dependency edges

- **Old behavior:** lockfile versions 1 and 2 did not identify the source scope
  of a dependency edge. When the same artifact appeared in several scopes,
  graph consumers could not determine which package copy an edge targeted.
- **New behavior:** newly resolved locks use version 3 and encode the target
  scope on every dependency edge. SBOM, tree, why, and workspace graph
  projections refuse an ambiguous legacy edge instead of silently omitting it.
- **Migration:** run `zolt resolve` for a project lock or
  `zolt resolve --workspace` for a workspace lock, then commit the regenerated
  version 3 `zolt.lock`.

## `zolt update` repurposed for dependency updates

- **Old behavior:** `zolt update` updated the installed Zolt binary — a redundant
  alias of `zolt self update`.
- **New behavior:** `zolt update` updates dependency, platform, and version-alias
  versions in `zolt.toml`. It never touches the Zolt binary.
- **Migration:** to update the Zolt binary, run `zolt self update` (unchanged and
  canonical). Scripts or habits that ran `zolt update` for self-update must switch
  to `zolt self update`.

See [Dependency Updates](../REFERENCE.md#dependency-updates) for the repurposed command.
