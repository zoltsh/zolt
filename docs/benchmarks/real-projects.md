# Real-Project Benchmarks

Generated benchmarks are controlled and repeatable, but public performance
claims also need recognizable Java projects. A real-project comparison is ready
only when its source, commit, adapter, commands, environment, and omissions are
checked in or included with the result.

## Rules

- Pin every upstream project to a commit SHA.
- Generate isolated Zolt, Maven default, Maven parallel, Gradle no-daemon,
  Gradle daemon, and Gradle parallel/configuration-cache overlays from the same
  copied source and resource trees.
- Declare the same compiler release, dependencies, and provided dependencies in
  every overlay.
- Warm dependency caches outside timed samples.
- Measure first clean, repeated clean, warm no-op, implementation change, and
  public-API change separately.
- Record exact tool versions, commands, raw samples, logs, and adapter coverage.
- Rotate tool order and report median, mean, p95, min, max, standard deviation,
  coefficient of variation, and a bootstrap median confidence interval.
- Require identical copied-source digests and compiled class sets before timing.
- Require every implementation and API mutation sample to produce distinct
  compiled bytecode that differs from an untimed, precompiled seed.
- Keep any build using the upstream project's original build files as a
  separately labeled native baseline.
- Keep omitted modules, tests, generators, native code, and packaging behavior
  next to every result; a subset must never be described as the full project.

## Configured Suite

The machine-readable source of truth is [projects.json](./projects.json).

| Project | Upstream tool | Pinned source set | Adapter |
| --- | --- | --- | --- |
| Spring PetClinic | Maven | Full main Java and resources | `benchmarks/adapters/spring-petclinic/coverage.json` |
| Apache Commons CLI | Maven | Full main Java and resources | `benchmarks/adapters/apache-commons-cli/coverage.json` |
| HikariCP | Maven | Full main Java and resources | `benchmarks/adapters/hikaricp/coverage.json` |
| Netty | Maven | `common` main Java and resources | `benchmarks/adapters/netty/coverage.json` |
| JUnit Framework | Gradle | `junit-platform-commons` main Java and resources | `benchmarks/adapters/junit-framework/coverage.json` |

HikariCP's current pinned `dev` source uses Maven. The earlier planned Gradle
label was stale and is intentionally not preserved as an “upstream” claim.

| Project | Branch | Commit |
| --- | --- | --- |
| Spring PetClinic | `main` | `51045d1648dad955df586150c1a1a6e22ef400c2` |
| Apache Commons CLI | `master` | `5ee80a0592c3126c7d54afe0e743717bdbc54057` |
| HikariCP | `dev` | `a4d93f4f85517f90e632b795486d7102e933d7ff` |
| Netty | `4.1` | `bb2ff68a1fb71cb4b0eb9a9e17b66c52aff680c6` |
| JUnit Framework | `main` | `b87db9fe6616cefe4c03e165e61f54bd9c76017b` |

## Running the Suite

Run all configured projects without the generated lane:

```sh
scripts/benchmark-suite \
  --skip-generated \
  --real-projects spring-petclinic,apache-commons-cli,hikaricp,netty,junit-framework \
  --zolt ~/.zolt/bin/zolt \
  --include-tuned-modes \
  --repeat 3
```

Run one adapter while debugging:

```sh
scripts/benchmark-real-project \
  --project junit-framework \
  --zolt ~/.zolt/bin/zolt \
  --include-tuned-modes \
  --repeat 1
```

The suite shares dependency caches across real-project lanes, but every tool
mode has isolated compiled outputs per project. Each project always runs Zolt,
Maven default, Gradle no-daemon, and Gradle daemon; publication profiles add
Maven parallel and Gradle parallel/configuration-cache. `nativeTool` records the
upstream project's build system rather than limiting overlay coverage. Apache
Commons CLI additionally runs the pinned checkout's original Maven build for
first clean, repeated clean, and no-op workflows. A dry run validates manifest,
pin, adapter, tool, and reporting contracts without cloning or timing:

```sh
scripts/benchmark-suite --skip-generated --real-projects spring-petclinic,apache-commons-cli,hikaricp,netty,junit-framework --real-project-dry-run
```

## Coverage Boundary

PetClinic, Commons CLI, and HikariCP cover their full main Java and resource
source sets in generated compiler overlays. This does not mean their complete
native Maven build lifecycle is reproduced. Netty and JUnit are named module
subsets because their complete reactors include build features outside the
current Zolt adapter scope. The separately labeled Commons CLI upstream-Maven
row is genuine native-build evidence but excludes tests, reports, site, and
release plugins. Tests, generators, and upstream release packaging are excluded
from all five overlay comparisons. The specialist Netty runner adds
dependency-resolution and thin-package rows but does not widen the source
coverage beyond `common`.

Repeated result labels follow the suite-wide predeclared outcome rule: a 5%
minimum effect, at least five paired samples, and a deterministic 95% bootstrap
confidence interval for the paired median-duration ratio. A lower median alone
does not establish a win; results that do not cross the effect and noise
thresholds are `inconclusive`.

Every uploaded result includes the coverage JSON so these limits remain visible
with the numbers.
