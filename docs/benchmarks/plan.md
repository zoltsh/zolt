# Benchmark Plan

The benchmark suite is product evidence, not a timing script. It publishes
repeatable runs that show how Zolt behaves on controlled enterprise-scale Java,
pinned upstream source sets, and its own source tree. Raw samples and correctness
evidence remain available so humans and models do not have to invent claims.

## Implemented Shape

The suite has three lanes:

1. `enterprise-generated`: a deterministic Java workspace generated from a
   versioned workload specification and built from identical sources by Zolt,
   Maven, Gradle no-daemon, and Gradle daemon.
2. `real-project-comparison`: pinned upstream projects copied into equivalent
   compiler overlays. These are explicitly main-source comparisons, not claims
   about the projects' complete native build systems.
3. `zolt-self-host`: a packaged Zolt binary builds and packages a clean copy of
   Zolt. This lane is deliberately non-comparative.

The enterprise lane is the centerpiece for scaling and end-to-end workflow
claims. Real-project lanes support only claims about the checked-in source and
build-feature boundary. Self-host results establish practical coverage without
being mixed into competitor ratios.

## Enterprise Workloads

Workload specifications live under `benchmarks/workloads/`:

- `smoke-v1.json` is the fast harness and pull-request validation profile.
- `enterprise-v1.json` is the publication profile: 200 library modules, a
  platform module, two applications, at least 1,000 Java files and 20,000 source
  lines, five repeated clean samples, and seven repeated workflow samples.

`scripts/benchmark-enterprise-fixture` deterministically creates equivalent
Zolt, Maven, and Gradle projects. The workload includes:

- `wide`, `layered`, and `chain` dependency graphs;
- multiple classes and methods per module;
- one shared platform module used by every library;
- compile, runtime, and provided dependencies;
- imported BOMs;
- Lombok annotation processing and generated getter output;
- resources and JUnit test sources in every member;
- a plain Java application and a Spring Boot application;
- library and application jars.

The generated source trees carry a common digest in `fixture-metadata.json`.
Changing the seed or workload fields changes the fixture deterministically
without committing generated source trees.

## Measured Workflows

The enterprise lane reports these workflows independently:

- dependency setup, excluded from compile-speed claims;
- first clean build, kept separate as a single cold tool-process sample;
- repeated clean build with warm dependency and tool caches;
- warm no-op build;
- leaf implementation change that changes the selected library's bytecode;
- shared public constant change with full fanout;
- resource-only change;
- test-source change build with the same selected sentinel tests;
- tagged benchmark-test run;
- package.

Every repeated row records raw samples, median, mean, p95, min, max, standard
deviation, coefficient of variation, and a deterministic bootstrap 95%
confidence interval for the median. Tool order rotates by workflow and sample.
Zolt's optional build-output cache is disabled so a user's machine configuration
cannot silently change the workload.

Real-project overlays report first clean, repeated clean, warm no-op, and
source-input change compile. They use the same statistics and rotating tool
order. Tests, code generation, and native release packaging remain excluded
unless an adapter explicitly adds them.

The self-host lane reports dependency setup, first and repeated clean builds,
warm no-op, a Zolt CLI source edit, and packaging. It never declares a competitor
winner.

## Correctness Gates

Timing is accepted only after correctness passes.

The enterprise lane checks:

- identical main class sets across all four tool modes;
- identical copied resource sets;
- annotation-processor output with `javap`;
- exactly one changed library output after the leaf implementation edit;
- the exact expected library fanout after an inlined public constant changes;
- identical compiled test class sets;
- equivalent class and resource entries in every produced jar.

The fanout gate exposed and now covers a Zolt correctness defect: Java
`ConstantValue` attributes are part of public ABI because consumers inline those
values. A changed dependency constant must invalidate workspace consumers.

Real-project lanes check source/resource overlay digests and compiled class-set
parity before timing. The self-host lane requires class output for every declared
source member, a true no-op skip, CLI-source invalidation, a packaged Zolt jar,
and restoration of the mutation target.

Any failed command or correctness check fails its lane and remains visible in
the combined suite result.

## Automation and Artifacts

`scripts/benchmark-suite` is the public entrypoint. It can run any combination
of the three lane types and writes:

- lane `samples.jsonl`, `summary.json`, `report.md`, `correctness.json`, and logs;
- a combined `suite-summary.json`;
- a deterministic `summary-brief.md` and `llm-summary.md`;
- optional `summary-ai.json` and `summary-ai.md`.

The `benchmarks` GitHub Actions workflow:

- validates every benchmark harness contract before running;
- supports released or freshly built native Zolt;
- uses the small workload on branch pushes;
- defaults manual runs to `enterprise-v1.json`, five clean samples, seven
  repeated samples, real-project lanes, and self-host coverage;
- uploads workload specs, adapter contracts, raw samples, correctness evidence,
  summaries, and logs;
- excludes cloned upstream source trees from artifacts.

Use a released native Zolt for public competitor claims. A branch-built zap is
the correct choice for validating a candidate Zolt change, but its build time is
not included in benchmark timing.

## Optional Model Summary

The structured result remains the source of truth. When `OPENAI_API_KEY` is
configured, `scripts/benchmark-openai-summary` reads only
`suite-summary.json`, requests a structured response, records model and request
metadata, and writes JSON plus Markdown. The step is non-blocking so evidence is
still published when the API is unavailable.

The prompt must keep first-clean results separate, name omissions, avoid
unsupported evidence grades, and never include secrets or full command logs.

## Publication Gate

A dated public result is publishable only when a manual GitHub run:

- uses `enterprise-v1.json` with at least five clean and seven repeated samples;
- includes all four tool modes;
- uses a released native Zolt;
- passes every correctness gate;
- reports the runner, JDK, tool versions, workload digest, and exact commands;
- uploads one complete artifact containing raw samples and logs.

Local smoke runs and branch-built runs are validation evidence, not public
performance claims. Copy a successful publication run into
`docs/benchmarks/results/` only after that gate passes.
