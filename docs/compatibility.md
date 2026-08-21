# Compatibility

Zolt's public surface is the installed `zolt` executable and the files it reads
or writes for users. Java packages in this repository are implementation details;
no module, including `zolt-framework-api`, is a supported third-party Java API or
SPI.

## Surface promises

| Surface | Compatibility promise |
| --- | --- |
| CLI command names and options | Public, non-hidden names are deprecated before removal. Hidden commands and options are internal. |
| Exit meanings | Stable for documented commands and independent of text or JSON formatting. `0` means the requested operation succeeded; a nonzero value means it did not. |
| Human-readable output | Intended for people and not a stable parsing interface. |
| `zolt.toml` | Public. Breaking key or semantic changes require a documented migration path in [`breaking-changes.md`](./breaking-changes.md). |
| `zolt.lock` | Public generated data. Readers support only the versions documented in [`breaking-changes.md`](./breaking-changes.md); regenerate when instructed. |
| Machine-readable JSON | Versioned per command. The stable commands listed below retain their v1 envelope. |
| Environment variables and user-global config | Public only when documented in command help or user documentation. Undocumented values are internal. |
| Generated artifact names and layouts | Public only when documented. Incidental files below `target/` are internal. |
| Java modules and packages | Internal and unsupported unless a future document explicitly declares a module to be public API. |

Preview commands and fields may change during `0.x`. A breaking change to a
declared public surface is recorded with the old behavior, new behavior, and
migration instructions.

## Stable JSON commands

The stable JSON contracts are:

- `zolt check --format json`
- `zolt outdated --format json`
- `zolt update --format json`
- `zolt toolchain status --format json`
- `zolt toolchain global status --format json`

`--json` remains a shorthand for the toolchain status commands. Their exit code
does not change with the selected format.

Every response from these commands has this top-level envelope:

```json
{
  "schemaVersion": 1,
  "command": "check",
  "status": "ok",
  "diagnostics": []
}
```

Command-specific fields follow the envelope. Runtime failures requested in JSON
also use it, set `status` to `failed`, emit at least one diagnostic, write JSON to
standard output, and return nonzero. Consumers must branch on `schemaVersion`
before interpreting command-specific fields and must not parse human-readable
output.

Other structured outputs are preview contracts unless their documentation says
otherwise. They may still carry a schema version, but that alone does not declare
the command stable.

### Outdated automation schema v2

`zolt outdated --format json --schema-version 2` is the stable automation
contract for exact dependency targets. Schema v1 remains the default and is not
extended with v2 fields.

Each v2 scope includes canonical mutation-root-relative `manifestPath` and
`lockfilePath` values. Each entry includes an opaque `zt1_` target ID plus
`updateable` and `updateBlocker`. Target IDs are scoped to one standalone project
or workspace and remain stable when current versions, candidates, fan-out, or
lockfile paths change. Callers must treat them as opaque and rediscover them from
the selected Zolt root rather than constructing or decoding them.

For a workspace, schema v2 also emits literal workspace-root `[platforms]` as a
distinct `workspace-root` scope. Its `manifestPath` is the policy manifest that
owns the entry (the workspace root `zolt.toml`), and its `lockfilePath` is
the aggregate root `zolt.lock`. When the root manifest is itself member `.`, the
ordinary member scope owns those entries and no duplicate root target is emitted.
Root-platform candidates must be visible through every distinct effective member
repository configuration; Zolt reports only the intersection. An intersected
candidate has a non-null `source` only when every effective member view
attributes it to the same repository identity. A matching root
and member platform declaration remains valid workspace policy, but schema v2
marks both the root target and the member literal or version-alias target as not
updateable until the declaration is consolidated at the workspace root. Schema
v1 remains unchanged.

Workspace member dependency discovery in schemas v1 and v2 uses effective
workspace-root-plus-member repositories and credentials. Targets still describe
and mutate the raw member manifest, and conflicting root/member repository
definitions fail closed. Unauthenticated metadata cache entries are isolated by
an opaque digest of repository ID and canonical URI. Authenticated repository
metadata is not cached, so it is unavailable to offline discovery and is never
used as transient-failure fallback.

Policy-driven workspace updates apply the same contextual blocker to mirrored
member platforms and their governing aliases. Dry-run reports them as skipped;
default and `--no-resolve` execution recompute blockers and revalidate the
effective workspace policy under the mutation lock before writing.

### Exact update automation schema v2

`zolt update --target-id ID --to VERSION --format json --schema-version 2`
is the matching stable write contract. The destination is caller-selected and
must be a supported, strictly newer fixed version. Exact mode does not consult
repository metadata; the default staged resolve proves availability and rolls
back both manifest and lockfile if resolution fails.

For workspace writes, the commit step revalidates every captured workspace
manifest after staged resolution. A concurrent change to the target, another
member, the workspace policy manifest, or the root lockfile fails closed without
overwriting that change. Exact execution also recomputes workspace-wide target
blockers after reacquiring the mutation lock, before either a resolved or
`--no-resolve` write.

Success output distinguishes the requested semantic change from actual effects
with `changed`, `applied`, `resolved`, and canonical `changedFiles` fields. It
also returns the exact target identity and alias fan-out. Valid schema-v2 machine
requests use the same envelope with `status: "failed"` and structured diagnostics
when validation, routing, revalidation, or resolution fails.

Policy-driven `zolt update --format json` remains schema v1. Schema v2 is not an
extension of policy-update output, and schema v1 is not extended with exact-
target fields.
