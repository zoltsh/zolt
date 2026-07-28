# Benchmark Plan

The benchmark suite is product evidence, not a timing script. It publishes
repeatable runs that show how Zolt behaves on controlled enterprise-scale Java,
pinned upstream source sets, and its own source tree. Raw samples and correctness
evidence remain available so humans and models do not have to invent claims.

## Implemented Shape

The suite has three lanes:

1. `enterprise-generated`: a deterministic Java workspace generated from a
   versioned workload specification and built from identical sources by Zolt,
   Maven default, Maven parallel, Gradle no-daemon, Gradle daemon, and Gradle
   parallel/configuration-cache.
2. `real-project-comparison`: pinned upstream projects copied into equivalent
   compiler overlays. These are explicitly main-source comparisons. An adapter
   may additionally declare a separately labeled build using the pinned
   project's original build files; Apache Commons CLI provides the first such
   Maven baseline.
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
bytecode-affecting implementation and public-API changes. Every change sample
must emit a new class digest that differs from a precompiled, untimed seed.
They use the same statistics and rotating tool order. Tests, code generation,
and native release packaging remain excluded unless an adapter explicitly adds
them.

The self-host lane reports dependency setup, first and repeated clean builds,
warm no-op, a Zolt CLI source edit, and packaging. It never declares a competitor
winner.

## Correctness Gates

Timing is accepted only after correctness passes.

The enterprise lane checks:

- identical main class sets across all enabled tool modes;
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
parity before timing, then require distinct bytecode for every implementation
and API mutation sample. A declared native upstream row must produce classes
from the pinned checkout using its original build files. The self-host lane
requires class output for every declared source member, a true no-op skip,
CLI-source invalidation, a packaged Zolt jar, and restoration of the mutation
target.

Any failed command or correctness check fails its lane and remains visible in
the combined suite result.

## Automation and Artifacts

`scripts/benchmark-profile` is the CI entrypoint. It validates a versioned
profile, expands it into lanes, and invokes `scripts/benchmark-suite`, which
remains the lower-level runner. The suite writes:

- lane `samples.jsonl`, `summary.json`, `report.md`, `correctness.json`, and logs;
- a combined `suite-summary.json`;
- a deterministic `summary-brief.md` and `llm-summary.md`;
- optional `summary-ai.json` and `summary-ai.md`.

Profiles live under `benchmarks/profiles/`:

- `smoke` builds the branch zap once and fans out small enterprise,
  real-project, and self-host validation lanes;
- `publishable` resolves one exact zap release and runs the canonical layered
  `enterprise-v1` lane plus all five pinned real-project lanes;
- `full` resolves one exact zap release and fans out both enterprise topologies,
  all five real-project lanes, and self-host.

Publication profiles retain the default Maven and Gradle modes and add
explicitly labeled tuned modes: Maven `-T 1C` and Gradle
`--parallel --configuration-cache`. Each mode has isolated project output and
dependency/tool caches.

The `benchmarks` GitHub Actions workflow:

- validates every benchmark harness contract before running;
- exposes only one manual input, the profile name;
- selects `smoke` automatically on branch pushes;
- builds a branch zap once or resolves a released zap once, then shares that
  exact version across every lane;
- runs profile lanes in parallel matrix jobs and aggregates their evidence;
- uploads workload specs, adapter contracts, raw samples, correctness evidence,
  profile digest, summaries, and logs in one combined artifact;
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

The prompt leads with a direct verdict, reports correctness failures before
timings, never hides a meaningful loss, reports statistically inconclusive
results as inconclusive, keeps first-clean results separate, and refuses
composite scores or unsupported evidence claims. It consumes the precomputed
outcomes instead of declaring a winner from medians. It never includes secrets
or full command logs.

## Outcome Rule

Repeated workflows use `paired-bootstrap-median-ratio-v1`. The harness pairs
same-index samples, computes duration ratios from the first named tool's
perspective, and requires:

- at least five paired samples;
- a minimum effect ratio of `1.05`;
- a deterministic 95% bootstrap confidence interval over 10,000 median-ratio
  resamples.

The first tool is `faster` only when the entire interval is below `1 / 1.05`,
and `slower` only when the entire interval is above `1.05`. Every other result
is `inconclusive`. A conclusive winner must be faster than every other enabled
tool under that rule. The lowest raw median remains visible but is not promoted
to a win.

## Publication Gate

A dated public result is publishable only when the complete manual
`publishable` or `full` profile:

- uses `enterprise-v1.json` with at least five clean and seven repeated samples;
- includes Zolt, both Maven modes, and all three Gradle modes;
- includes all five pinned real-project lanes at the same sample floor;
- includes at least one genuine native upstream-build baseline;
- uses the exact released native Zolt resolved once by the planning job;
- passes every correctness gate;
- reports the profile digest, resolved Zolt version, runner, JDK, tool versions,
  workload digest, and exact commands;
- uploads one complete artifact containing raw samples and logs.

Local smoke runs and branch-built runs are validation evidence, not public
performance claims. Copy a successful publication run into
`docs/benchmarks/results/` only after that gate passes.
