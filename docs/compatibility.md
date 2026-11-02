# Compatibility

Zolt's public surface is the installed `zolt` executable and the files it reads
or writes for users. Java packages in this repository are implementation details;
no module, including `zolt-framework-api`, is a supported third-party Java API or
SPI.

## Zolt 1.0 release scope

The supported lifecycle is Java dependency resolution, build, run, JUnit and
Spock/Groovy tests, integration tests, coverage, dependency-ordered workspaces,
JAR/uber-JAR/WAR and Spring Boot JVM packaging, Maven publication, and plain
GraalVM Native Image. Managed JDKs, locked/offline resolution, and verified
build-output reuse support that lifecycle. Changes to Java source inputs compile
the complete affected source set; selective Java recompilation is internal.

Native CLI distributions target Linux and macOS on x64 and arm64. The 1.0
release qualification baselines are Ubuntu 24.04 with glibc and macOS 15 on both
architectures. Older OS versions and Linux musl distributions are outside the
qualified matrix. Windows host support is experimental, unqualified for 1.0,
and has no published native binary. The distributed CLI uses GraalVM's `-march=compatibility`
CPU baseline: x86-64 on x64 and ARMv8-A on arm64, without requiring x86-64-v3
or the build runner's CPU extensions. These CPU settings do not lower the OS
baselines above or change the native-image options for users' own projects.

Windows discovery is disabled in default CI. Maintainers can explicitly enable
`discover_windows` when manually dispatching `ci.yml`; the non-blocking job
preserves failures and uploads its log. The 2026-11-01 discovery run at commit
`83c73e2` failed with `Could not run javac`. A known blocker is the external javac
backend's direct argument list: at that commit, the 343 `zolt-build` source paths
under `D:\a\zolt\zolt\` occupied 34,605 characters before compiler options or
classpath entries. This already exceeds [Windows' 32,767-character command-line
limit](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw).
Windows requires argument-file support and separate qualification before it can
join the supported matrix.

The following features are disabled by default and require
`ZOLT_EXPERIMENTAL=1` in the environment of each command that uses them:

| Experimental feature | Boundary |
| --- | --- |
| Kotlin/JVM | Main/test compilation, compiler plugins, KAPT, KSP, and generated Kotlin. See the [support matrix](./kotlin-jvm-support.md). |
| Groovy main compilation | Groovy-only and joint Java/Groovy main source sets. Existing Spock/Groovy test support remains in the stable lifecycle. |
| Quarkus | Framework commands, augmentation, and Quarkus package mode. Quarkus native images remain unsupported. |
| Spring Boot AOT/native | The existing Spring Boot 3.3 / Java 21 canary. Spring Boot JVM packaging remains supported. |

The opt-in enables development features; it does not extend 1.0 compatibility
promises to them. Experimental options, configuration, and output may change in
minor releases. Micronaut native processing and arbitrary Maven/Gradle plugin
execution remain unsupported.

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

These promises apply to the supported lifecycle and explicitly stable output
contracts. Experimental features and other preview contracts are excluded. A
breaking change to a stable public surface requires a major version and a record
of the old behavior, new behavior, and migration instructions.

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
