# Kotlin/JVM preview support

This matrix defines the Kotlin/JVM workflows Zolt currently qualifies. Kotlin
support is a bounded preview: a row marked **qualified** has repository
integration or smoke coverage, while a compiler version being **accepted** does
not mean every patch release has been exercised independently.

## Toolchain boundary

| Contract | Current boundary |
| --- | --- |
| Kotlin compiler | Stable Kotlin 2.2.x releases are accepted. Other release lines and prereleases fail before generated-source work, cache restoration, or output cleanup. |
| Exercised compiler | The real-compiler integration fixtures, CLI lifecycle tests, examples, and managed-JDK smoke currently pin Kotlin 2.2.0. Later 2.2.x patches are accepted but are not all separate CI matrix entries. |
| Standard library | Applications declare `org.jetbrains.kotlin:kotlin-stdlib` as an ordinary dependency at the selected compiler version. Compiler artifacts stay in the isolated, checksum-verified tool closure. |
| Java toolchain | Kotlin/JVM targets Java 8 or newer. CI exercises Kotlin 2.2.0 with a managed Temurin 8 JDK and with the Java 21 build environment. Host-platform API mode is a separate fingerprinted configuration. |
| Compiler options | Only the documented bounded arguments and built-in plugin selectors are accepted. Version-sensitive options are checked before reuse or mutation, and an unsupported-option compiler diagnostic fails the build even when the compiler exits zero. |

## Workflow matrix

| Workflow | Status | Boundary |
| --- | --- | --- |
| Kotlin-only main compilation | **Qualified preview** | Cleaned full-scope compilation when inputs change; an unchanged fingerprint may skip the compiler. |
| Mixed Java/Kotlin main compilation | **Qualified preview** | `kotlinc` analyzes the admitted Java/Kotlin source set, then `javac` compiles authored Java against Kotlin output. Circular Java/Kotlin declarations are covered. |
| Kotlin-only or mixed unit tests | **Qualified preview** | Uses the member's own main output as the sole Kotlin friend path. Java/Kotlin test cycles, selection, execution, and cache restoration are covered. |
| Kotlin-only or mixed integration tests | **Qualified preview** | Uses the bounded test compiler path and separate integration-test output. Standalone and workspace cold, warm, and restored runs are covered. |
| Workspaces | **Qualified preview** | API and implementation dependencies participate in ordered invalidation through class ABI and Kotlin module metadata. Dependency-member `internal` declarations remain inaccessible. |
| KAPT for main, unit, and integration lanes | **Qualified preview** | Uses the version-aligned isolated KAPT plugin and processor classpath. Generated Java is visible to Kotlin and Java, and final `javac` processing is disabled to prevent a second processor run. |
| KSP2 for main, unit, and integration lanes | **Qualified preview** | Uses separately locked engine and processor closures and staged publication of Kotlin, Java, and resources. KSP runs non-incrementally before every matching command; see the reuse table below. |
| Pre-generated, exec, OpenAPI, and Protobuf Kotlin sources | **Qualified preview** | Owned or protected generated roots enter discovery before compilation and participate in output-integrity and reuse decisions. |
| Built-in compiler plugins | **Qualified preview** | `serialization`, `spring`, `micronaut`, `jpa`, and `power-assert` are closed, version-aligned selectors. They do not add application runtime libraries. |
| Locked and offline builds | **Qualified preview** | After the lockfile, artifacts, compiler closure, and selected JDK are present, main/test/package workflows run with networking unavailable. Missing or altered inputs fail closed. |
| Local output-cache restoration | **Qualified preview** | Main, unit-test, integration-test, KAPT, KSP, and workspace fixtures cover restoration and subsequent edit/failure recovery. |
| Remote output cache | **Shared cache contract** | Kotlin outputs use the same content-addressed keys, compiler identity, integrity checks, and complete-output restoration as the generic remote backend. Transport is not a separate Kotlin compiler path. |
| Run and package | **Qualified preview** | `zolt run`, thin JAR plus `run-package`, and uber JAR workflows retain application Kotlin runtime dependencies while excluding compiler tooling. |
| Library publication | **Qualified preview** | Published classes and authored Kotlin sources are consumable from a fresh project. Kotlin Javadoc publication is rejected because Dokka is outside the preview. |

## Reuse and performance behavior

| Situation | Current behavior |
| --- | --- |
| Unchanged Kotlin or mixed source set without KSP | The compile fingerprint may skip Kotlin and Java compilation completely. |
| Changed Kotlin or Java input in a Kotlin-bearing source set | Zolt cleans owned outputs and recompiles the complete affected source set. Selective Kotlin recompilation is not claimed. |
| Verified output-cache hit | Zolt restores the complete class and `META-INF/*.kotlin_module` inventory without launching the compiler. |
| KAPT-enabled no-op | KAPT is part of compilation. It does not run when the ordinary compile fingerprint skips or a verified output is restored. |
| KSP-enabled no-op | KSP currently runs before the compile fingerprint check with its incremental state disabled. Byte-identical generated output can let compilation skip, but the command is not a cheap generator no-op. |
| `--no-build-cache` | Bypasses output-cache restore and storage only. It does not disable the unchanged-input fingerprint skip. |

Performance reports must distinguish whole-command latency from the later
"compilation skipped" decision, especially for KSP-enabled projects. The
benchmark harness and any published result remain the authority for timing
claims; this matrix records behavior, not speed.

## Explicitly outside the preview

- Kotlin scripts (`.kts`).
- Mixed Groovy/Kotlin source sets, including mixed test source sets.
- Kotlin-bearing JPMS source sets containing `module-info.java`.
- Kotlin tests in a Quarkus member until that workspace model carries explicit
  Kotlin test roots.
- Arbitrary Kotlin compiler-plugin coordinates or plugin options.
- Kotlin Javadoc publication without Dokka.
- Kotlin compiler lines other than stable 2.2.x, including prereleases.
- Fine-grained Kotlin incremental compilation and incremental or skipped KSP
  execution.

The complete manifest grammar and option catalog remain in the
[Reference](../REFERENCE.md#kotlinjvm-main-compilation-preview).
