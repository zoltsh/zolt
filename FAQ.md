# FAQ

## Why another build tool?

Java does not need another programmable build framework. It needs one tool that
owns the project lifecycle.

Zolt handles dependencies, JDKs, builds, tests, workspaces, packaging,
publishing, native images, and CI from one project model.

## Why not Rust?

Cargo and uv got the workflow right. Rust is not the workflow.

Zolt is Java because Java is enough. It builds itself and ships as a native CLI
through GraalVM Native Image. If that stops being true, we'll rewrite it in
Rust, ship both, and keep them in lockstep. The CLI, `zolt.toml`, and
`zolt.lock` are the contract.

## Why not Maven?

Zolt keeps the useful parts: Maven coordinates, repositories, BOMs, and
published metadata. It drops XML, lifecycle indirection, and a plugin for every
basic job.

## Why not Gradle?

Gradle can make a build do almost anything because the build is executable
software. That power is also the problem.

Zolt chooses a bounded model: locked dependencies, explicit plans,
deterministic packaging, and built-in behavior. If your build requires
arbitrary plugin code, keep Gradle.

## Why not Bazel?

Bazel is built for large graphs, polyglot repositories, and remote execution.
Zolt is built for Java projects.

You should not need a build platform team to compile, test, package, and
publish a service. If you do need remote execution across a huge monorepo,
Bazel may be the right answer.

## Is Zolt a wrapper?

No. Zolt resolves dependencies, manages JDKs, compiles sources, runs tests,
packages artifacts, publishes releases, and builds itself. Maven and Gradle are
migration inputs, not runtime dependencies.

## What about plugins?

Zolt supports declared tasks, exec steps, annotation processors, generated
sources, and framework packaging. It does not execute arbitrary Maven or Gradle
plugin behavior.

That limit is deliberate. Fewer extension points mean fewer ways for two builds
to mean different things.

## Can I migrate?

Run `zolt explain --source auto`. Zolt audits Maven or Gradle inputs, reports
blockers, scores the migration, and can emit a draft `zolt.toml`.

It will not pretend unsupported plugin behavior is supported.

## Is it fast?

Benchmark it. We do.

The [public harness](./docs/benchmarks/) compares Zolt, Maven, and Gradle on
equivalent generated workspaces and pinned real projects. Claims require dated
results and raw samples.

## What about Windows?

Native releases are published for Linux and macOS on x64 and arm64. Windows
host support is experimental; no Windows native binary is published yet.
