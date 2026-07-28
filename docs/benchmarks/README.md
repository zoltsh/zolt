# Benchmarks

Zolt benchmark claims should be backed by repeatable evidence, not a single
headline number.

The suite combines controlled generated Java workspaces, pinned real-project
lanes, specialist large-source comparisons, and optional OpenAI summaries over
the structured result. See [plan.md](./plan.md) for the benchmark architecture.
The real-project lane manifest lives in [projects.json](./projects.json).

The CI entrypoint is `scripts/benchmark-profile`; `scripts/benchmark-suite`
remains the lower-level runner for local experiments. Profiles validate and
expand into selected benchmark lanes, while the suite writes one summary and
keeps each lane's raw evidence under the artifact. The primary enterprise lane
uses a versioned workload spec
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
  --include-tuned-modes \
  --self-host
```

Useful variants:

```sh
scripts/benchmark-suite --topology wide --modules 100 --repeat 7 --include-gradle-daemon
scripts/benchmark-suite --topology layered --repeat 7 --include-gradle-daemon --include-tuned-modes
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

The generated enterprise lane can still be used directly while debugging:

```sh
scripts/benchmark-enterprise \
  --workload benchmarks/workloads/enterprise-v1.json \
  --topology layered \
  --repeat 7 \
  --clean-repeat 5 \
  --include-gradle-daemon \
  --include-tuned-modes
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

The manual `benchmarks` workflow has one input: `profile`.

| Profile | Zolt | Work |
| --- | --- | --- |
| `smoke` | Branch-built zap | Small enterprise lane, five one-sample real-project lanes, and one-sample self-host |
| `publishable` | Resolved zap release | Layered `enterprise-v1` plus five pinned real-project lanes, each with 5 clean and 7 repeated samples |
| `full` | Resolved zap release | Layered and wide enterprise lanes, five real projects, and self-host |

Run the canonical publication profile with:

```sh
gh workflow run benchmarks.yml --ref main -f profile=publishable
```

The workflow resolves the selected release channel once, records the exact Zolt
version in every lane, and then runs profile lanes as parallel matrix jobs. A
final aggregation job uploads one combined artifact containing every lane's raw
samples, logs, correctness evidence, deterministic summaries, and profile
digest. Publication profiles include Maven default, Maven parallel, Gradle
no-daemon, Gradle daemon, and Gradle parallel/configuration-cache beside Zolt.
The tuned modes are never substituted for or blended with the defaults. `full`
keeps the same one-option interface while adding a wide enterprise lane and
self-host evidence.

Pushes to `benchmark-improvements` select `smoke` automatically. Those runs build
and release-verify the branch zap once, then share it across the parallel lane
jobs. Smoke results are merge gates, not public performance evidence.

Profile definitions are versioned under `benchmarks/profiles/`. Inspect the
resolved contract or matrix locally with:

```sh
scripts/benchmark-profile show --profile publishable
scripts/benchmark-profile matrix --profile full
```

To enable model-generated summaries in GitHub Actions, add a repository Actions
secret named `OPENAI_API_KEY`. Optional repository variables:

- `OPENAI_MODEL`, default `gpt-5.5`;
- `OPENAI_REASONING_EFFORT`, default `high`.

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
scripts/benchmark-statistics-test
scripts/benchmark-suite-test
scripts/benchmark-profile-test
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
Maven default, Maven parallel, Gradle no-daemon, Gradle daemon, and Gradle
parallel/configuration-cache overlays from the same checked-in adapter contract:

```sh
scripts/benchmark-real-project --project spring-petclinic --repeat 5 --include-tuned-modes
scripts/benchmark-real-project --project apache-commons-cli --repeat 5 --include-tuned-modes
scripts/benchmark-real-project --project netty --repeat 3 --sample-timeout 3600 --include-tuned-modes
scripts/benchmark-real-project --project junit-framework --repeat 3 --include-tuned-modes
```

Those runs clone the pinned upstream commit into the benchmark output directory,
warm dependency caches outside the timed samples, and record first clean,
repeated clean, warm no-op, implementation-change, and public-API-change timings.
Each change sample must produce a distinct compiled class digest; a comment-only
or otherwise non-semantic edit fails the lane. The mutation class is seeded and
compiled outside timing so every measured sample is an edit, not a first-time
class addition. Tool order rotates by sample; reports include p95, variation,
confidence intervals, source digests, compiled-class parity, adapter scope, and
omissions. Apache Commons CLI also runs the pinned checkout's original Maven
build directly for clean and no-op upstream baselines. That row is labeled
`Upstream Maven`; it is not conflated with the generated Maven overlay.
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
- publish the supplied `faster`, `slower`, or `inconclusive` outcome, not a
  winner inferred only from the lowest median;
- keep raw samples and paired comparison evidence available;
- label Maven default versus parallel and Gradle no-daemon versus daemon versus
  parallel/configuration-cache;
- avoid claims from machines with missing competitors, failed setup commands, or
  mixed cache states.

The outcome rule is fixed before a run: compare paired duration ratios, require
at least five paired samples, and compute a deterministic 95% bootstrap
confidence interval for the median ratio. Zolt is `faster` only when the entire
interval is below `1 / 1.05`, and `slower` only when it is above `1.05`.
Everything else is `inconclusive`. First-clean rows have one sample and are
therefore always inconclusive; their raw timings remain useful context.

The README should stay conservative until this directory contains dated evidence
from a clean machine. A good public claim is specific: for example, "on this
machine and pinned workload, Zolt was faster/slower/inconclusive against the
fastest comparator under the declared outcome rule; the observed medians were N
ms and M ms."
