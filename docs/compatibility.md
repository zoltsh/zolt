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
