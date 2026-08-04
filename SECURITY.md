# Security Policy

## Supported Versions

Zolt is pre-1.0. Security fixes are handled on the latest published release and
the current `main` branch.

Published GitHub Releases are immutable and are never replaced. Unsupported older
releases remain available as exact historical artifacts; security fixes ship in a new
release.

| Version | Security support |
| --- | --- |
| Latest published release | Yes |
| Current `main` branch | Yes |
| Older snapshots and prerelease releases | No |

## Reporting a Vulnerability

Please report suspected vulnerabilities privately through GitHub's Security tab
for this repository. Choose **Report a vulnerability** and include as much of
the following as you can:

- The affected Zolt version, release channel, or commit.
- Your operating system, CPU architecture, and JDK version.
- The command, project shape, or archive/update flow involved.
- A minimal reproduction or enough detail for maintainers to reproduce it.
- Any logs, stack traces, or generated artifacts that help explain impact.

Do not include secrets, private source code, or credentials in the report. If a
reproduction needs sensitive material, describe the shape of the data instead.

## Scope

Reports are in scope when they affect Zolt itself, including:

- The CLI, project parser, dependency resolver, build/test/package flows, and
  workspace behavior.
- Installer scripts, release archives, release indexes, self-update behavior,
  checksums, and signature verification.
- Generated artifacts or metadata produced by Zolt in a way that can create a
  security impact for downstream users.

Reports are usually out of scope when they are limited to:

- A vulnerability in a third-party dependency with no demonstrated Zolt-specific
  impact.
- A sample application under `examples/` that is not part of the Zolt runtime or
  distribution path.
- User project code that Zolt builds but does not generate or modify.

## Disclosure

Maintainers will review private reports, confirm scope, prepare a fix when one
is needed, and coordinate public disclosure after affected users have a
reasonable update path.
