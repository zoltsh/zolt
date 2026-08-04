<p align="center">
  <img src="./logo.svg" alt="zolt" width="720">
</p>

<p align="center">
  <strong>A fast, self-hosted Java build tool.</strong>
</p>

<p align="center">
  Dependencies, toolchains, builds, tests, workspaces, packaging, publishing,
  native images, and CI.
</p>

<p align="center">
  <a href="#install">Install</a>
  <span> · </span>
  <a href="#start">Start</a>
  <span> · </span>
  <a href="#features">Features</a>
  <span> · </span>
  <a href="#cli">CLI</a>
  <span> · </span>
  <a href="./USAGE.md">Usage</a>
  <span> · </span>
  <a href="./FAQ.md">FAQ</a>
  <span> · </span>
  <a href="#benchmarks">Benchmarks</a>
</p>

<br />

## Install

```sh
curl --proto '=https' --proto-redir '=https' --tlsv1.2 -fsSL \
  https://dist.zolt.sh/install.sh | sh
```

The stable URL serves a reviewed bootstrap that pins an immutable GitHub-hosted
installer and its SHA-256. That installer resolves the current zap release, accepts
only exact `zoltsh/releases` archive and checksum URLs, verifies the archive, and
records the signed channel used by `zolt self update`.

## Start

```sh
zolt init hello
cd hello
zolt test
zolt package
```

Zolt creates the project, resolves `zolt.lock`, runs its JUnit test, and
packages it.

## Model

```txt
zolt.toml      project model
zolt.lock      resolved packages
zolt           build, test, package, release
```

## Features

<table>
  <tr>
    <td width="50%" valign="top">
      <strong>Projects and workspaces</strong><br>
      Projects, tasks, aliases, builds, runs, and dependency-ordered workspaces.
    </td>
    <td width="50%" valign="top">
      <strong>Dependencies</strong><br>
      Editing, BOMs, updates, deterministic resolution, and graph inspection.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <strong>Tests and coverage</strong><br>
      JUnit, Spock, integration tests, selection, sharding, profiling, and JaCoCo.
    </td>
    <td width="50%" valign="top">
      <strong>Packages and releases</strong><br>
      Thin, uber, WAR, Spring Boot, Quarkus, native images, and Maven publishing.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <strong>Toolchains and CI</strong><br>
      Managed JDKs, build caches, quality gates, SBOMs, and license checks.
    </td>
    <td width="50%" valign="top">
      <strong>Insight and migration</strong><br>
      Plans, IDE models, JSON output, and Maven or Gradle migration audits.
    </td>
  </tr>
</table>

<p align="center">
  <strong><a href="./FEATURES.md">All features</a></strong>
</p>

## CLI

Human-readable output. Stable JSON. Plans and dry runs. Errors with next steps.

```console
$ zolt plan --target package
Status: blocked
- lockfile [resolve] blocked - Dependency graph is not locked yet.
  next: Run `zolt resolve` first, then rerun `zolt plan`.
```

Use `zolt --list` for the command map or `zolt help <command>` for command help.

## Benchmarks

Benchmark claims should point to repeatable evidence. See
[docs/benchmarks](./docs/benchmarks/) for the public harness that compares Zolt,
Maven, and Gradle on generated multi-module Java workspaces.

## FAQ

Why not Rust, Maven, Gradle, or Bazel? See [FAQ.md](./FAQ.md).

## Security

Please report suspected vulnerabilities privately. See [SECURITY.md](./SECURITY.md).

## License

Apache-2.0. See [LICENSE](./LICENSE).
