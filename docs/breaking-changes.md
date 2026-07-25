# Breaking Changes

Breaking changes to Zolt's CLI and configuration, newest first. Each entry names
the old behavior, the new behavior, and how to migrate.

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
