# Breaking Changes

Breaking changes to Zolt's CLI and configuration, newest first. Each entry names
the old behavior, the new behavior, and how to migrate.

## Lockfile version 5 preserves optional boundaries and conflict provenance

- **Old behavior:** workspace optional dependencies could cross into downstream
  member classpaths, a whole-workspace SBOM flattened member-qualified graphs,
  and rematerialization could replace the original member conflict evidence.
- **New behavior:** newly resolved locks use version 5. Member graph facts carry
  optional-only reachability captured before path-specific exclusion contexts
  are flattened, `[[conflict]]` entries carry member attribution, and identities
  with any member-qualified graph facts must cover every attributed member.
  Whole-workspace SBOMs create distinct graph `bom-ref` contexts when members
  share a PURL but have different outgoing edges. Workspace resolution also
  rejects mixed local and repository targets at the same package, version, and
  variant even when they occur in different scopes; scope cannot distinguish
  two byte identities in Maven or CycloneDX. Workspace graph-consuming commands
  refuse readable pre-v5 locks rather than treating missing optional facts as
  required. Per-member published SBOMs apply those optional-only facts while
  traversing sibling graphs and remove both the omitted component and its edge.
  Finally, ordinary workspace dependencies may target only `thin` members:
  executable, Quarkus, uber, WAR, and BOM members are application artifacts,
  not reusable library JARs.
- **Migration:** run `zolt resolve` for a project lock or
  `zolt resolve --workspace` for a workspace lock, then commit the regenerated
  version 5 `zolt.lock`. Make every affected consumer use the workspace member
  or give the local project a distinct version. Split shared code needed from an
  application-packaged member into a separate `thin` workspace member.

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

See [Dependency Updates](../USAGE.md#dependency-updates) for the repurposed command.
