# Benchmarks

Zolt benchmark claims should be backed by repeatable evidence, not a single
headline number.

The suite combines controlled generated Java workspaces, pinned real-project
lanes, specialist large-source comparisons, and optional OpenAI summaries over
the structured result. See [plan.md](./plan.md) for the benchmark architecture.
The real-project lane manifest lives in [projects.json](./projects.json).

The public entrypoint is `scripts/benchmark-suite`. It runs selected benchmark
lanes, writes one suite-level summary, and keeps each lane's raw evidence under
the suite artifact. The primary enterprise lane uses a versioned workload spec
to generate byte-for-byte identical Java sources and equivalent dependency
graphs for Zolt, Maven, and Gradle, then records wall-clock samples for:

- dependency setup;
- first and repeated clean builds;
- warm no-op build;
- leaf and shared-API fanout changes;
- resource changes;
- test-source change build and tagged test run;
- package.

Generated workspaces support three graph shapes:

- `wide` (the default): independent libraries feed one app, exposing one large
  parallel compilation wave;
- `layered`: fixed-width dependency waves expose scheduling across a DAG;
- `chain`: a serial dependency chain retained as a control, not as parallelism
  evidence.

The enterprise workload also covers BOMs, dependency scopes, Lombok annotation
processing, resources, tests, multiple applications, and a Spring Boot app. Every
report records topology, fanout, source volume, sample counts, strong statistics,
and correctness evidence so unlike or incorrect workloads are not silently
compared.

The script writes raw JSON-lines samples, command logs, a JSON summary, and a
Markdown report under `target/benchmarks/competitors` by default.

```sh
scripts/benchmark-suite \
  --enterprise-workload benchmarks/workloads/enterprise-v1.json \
  --topologies wide,layered \
  --clean-repeat 5 \
  --repeat 7 \
  --include-gradle-daemon \
  --self-host
```

Useful variants:

```sh
scripts/benchmark-suite --topology wide --modules 100 --repeat 7 --include-gradle-daemon
scripts/benchmark-suite --topology layered --layer-width 8 --modules 100 --repeat 7
scripts/benchmark-suite --topology chain --modules 40 --repeat 5
scripts/benchmark-suite --zolt ~/.zolt/bin/zolt
scripts/benchmark-suite --enterprise-workload benchmarks/workloads/smoke-v1.json --repeat 1 --clean-repeat 1
scripts/benchmark-suite --skip-generated --self-host --zolt ~/.zolt/bin/zolt
scripts/benchmark-suite --topology wide --generated-summary target/benchmarks/competitors/generated-java-workspace-wide/summary.json
scripts/benchmark-suite --real-projects spring-petclinic,apache-commons-cli --repeat 5
scripts/benchmark-suite --skip-generated --real-project netty --repeat 1 --real-project-sample-timeout 3600
scripts/benchmark-suite --skip-generated --real-project netty --repeat 3 --real-project-sample-timeout 3600
scripts/benchmark-suite --skip-generated --real-projects spring-petclinic,netty --real-project-dry-run
scripts/benchmark-competitors --topology wide --modules 200 --skip-maven --skip-gradle
```

The generated-lane script can still be used directly while debugging:

```sh
scripts/benchmark-competitors --topology wide --modules 40 --repeat 5 --include-gradle-daemon
```

After a direct generated-lane run, generate a suite-level summary:

```sh
scripts/benchmark-suite-summary \
  --summary target/benchmarks/competitors/summary.json \
  --output target/benchmarks/competitors/suite-summary.json
```

That writes:

- `summary-brief.md` for a deterministic suite summary;
- `suite-summary.json` as the stable contract for CI, artifacts, and model
  summarization;
- topology-specific lane detail files such as `generated-java-workspace-wide/`;
- `llm-summary.md` as a compatibility alias for the deterministic summary.

To generate a model summary locally:

```sh
OPENAI_API_KEY=... scripts/benchmark-openai-summary \
  --input target/benchmarks/competitors/suite-summary.json
```

Use `--dry-run` to write the request payload without calling the API.

No OpenAI call happens unless `OPENAI_API_KEY` is configured. CI still publishes
the deterministic summary when the key is absent.

## GitHub Actions

Use the manual `benchmarks` workflow for public runs. It installs or builds a
native Zolt binary, runs the suite harness, writes the deterministic compact summary
into the job summary, optionally appends a model-generated summary, and uploads
the report, raw samples, JSON summaries, prompt context, and command logs as
workflow artifacts.

To enable model-generated summaries in GitHub Actions, add a repository Actions
secret named `OPENAI_API_KEY`. Optional repository variables:

- `OPENAI_MODEL`, default `gpt-5.5`;
- `OPENAI_REASONING_EFFORT`, default `high`;
- `BENCHMARK_AI_SUMMARY=false` to disable the model step without removing the
  secret.

Use `zolt_source=release` for publishable comparisons. It installs the selected
release channel and avoids mixing Zolt build time into the benchmark setup. Use
`zolt_source=build` only when measuring the checked-out branch's native binary.
Pushes to `benchmark-improvements` build and release-verify a branch zap, run
the correctness-gated smoke workload, exercise every configured real-project
adapter, and run the non-comparative self-host lane. One-sample push results are
merge gates, not publishable performance evidence. Manual runs default to the
200-library `enterprise-v1` workload, 7 repeated samples, 5 repeated clean
samples, `wide,layered` topologies, all five real-project comparisons, self-host,
and Gradle daemon coverage.
For a Netty-only validation run, dispatch the workflow with
`skip_generated=true`, `real_projects=netty`, `repeat=1`, and
`real_project_sample_timeout=3600`.
That lane measures the same filtered Netty `common` main-source overlay with
Zolt, Maven, Gradle no-daemon, and Gradle daemon; native platform modules, the
rest of the reactor, and tests are explicitly omitted.

The workflow installs a pinned Gradle distribution directly instead of using
`gradle/actions/setup-gradle`. That keeps the GitHub summary dedicated to the
benchmark report and avoids unrelated Gradle action cache/build-scan summaries.
Uploaded real-project artifacts keep summaries, samples, and command logs, but
exclude the checked-out upstream source trees.
Adapter coverage under `benchmarks/adapters/` is included so real-project
comparisons carry their scope and omissions with the data.

## Specialist Lanes

The suite also preserves two focused manual comparisons that answer questions
the module-count lane cannot.

The large-source lane defaults to eight modules, 500 classes per module, and 30
methods per class: roughly half a million generated Java lines. It measures cold
dependency setup, warm no-op builds, implementation-only changes, public ABI
changes, full tests, and a selected downstream test for Zolt, Maven, and Gradle.

```sh
scripts/benchmark-large-source --zolt ~/.zolt/bin/zolt --repeat 3 --include-gradle-daemon
scripts/benchmark-large-source-report \
  target/benchmarks/large-source/large-source-compare-summary.jsonl
```

The Netty lane generates Zolt and Maven overlays from the same filtered pinned
Netty `common` Java sources, dependencies, and Java level. It is a controlled
source-subset comparison, not a full-reactor comparison. Package rows stay
separate because the two thin jars are not asserted to be byte-identical.

```sh
scripts/benchmark-netty-compare \
  --netty-dir /path/to/netty-at-bb2ff68 \
  --zolt ~/.zolt/bin/zolt \
  --repeat 3
```

Validate all benchmark contracts without doing a production-sized run:

```sh
scripts/benchmark-suite-test
scripts/benchmark-enterprise-test
scripts/benchmark-self-host-test
scripts/benchmark-large-source-test
scripts/benchmark-large-source-report-test
scripts/benchmark-netty-compare-test
scripts/benchmark-real-project-test
```

## Real Projects

Generated workspaces are useful for controlled scaling evidence, but they are
not enough for public performance claims. Real-project benchmarks should use
pinned upstream commits plus Zolt adapters that build the same meaningful source
set. See [real-projects.md](./real-projects.md) for the project policy and
initial candidate suite.
`projects.json` is the machine-readable version used by the suite runner.

The real-project runner checks out a pinned commit and generates isolated Zolt,
Maven, Gradle no-daemon, and Gradle daemon overlays from the same checked-in
adapter contract:

```sh
scripts/benchmark-real-project --project spring-petclinic --repeat 5
scripts/benchmark-real-project --project apache-commons-cli --repeat 5
scripts/benchmark-real-project --project netty --repeat 3 --sample-timeout 3600
scripts/benchmark-real-project --project junit-framework --repeat 3
```

Those runs clone the pinned upstream commit into the benchmark output directory,
warm dependency caches outside the timed samples, and record first clean,
repeated clean, warm no-op, and source-input change timings for all four modes.
Tool order rotates by sample; reports include p95, variation, confidence
intervals, source digests, compiled-class parity, adapter scope, and omissions.
The separate `benchmark-netty-compare`
runner remains a specialist lane with additional dependency and thin-package
rows for the same smaller `common` source subset.

## Publishing Results

When publishing benchmark evidence:

- include the generated `report.md`, `summary.json`, `suite-summary.json`, and
  `samples.jsonl`;
- include `summary-brief.md` and, when generated, `summary-ai.json` plus
  `summary-ai.md`;
- include tool versions, JDK version, OS, CPU architecture, module count,
  topology, source volume, and repeat count;
- keep the first clean build separate from repeated no-op, leaf-change, and
  root-change workflows;
- compare medians and keep raw samples available;
- say whether Gradle was measured with or without the daemon;
- avoid claims from machines with missing competitors, failed setup commands, or
  mixed cache states.

The README should stay conservative until this directory contains dated evidence
from a clean machine. A good public claim is specific: for example, "on this
machine, for this generated 100-module workspace, Zolt's median warm no-op build
was N ms versus Maven M ms and Gradle G ms."
