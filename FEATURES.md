# Zolt Features

Zolt covers Java projects from setup through release. See
[USAGE.md](./USAGE.md) for details.

<table>
  <tr>
    <td width="50%" valign="top">
      <strong>Develop</strong><br>
      <a href="#cli">CLI</a> ·
      <a href="#projects">Projects</a> ·
      <a href="#dependencies">Dependencies</a> ·
      <a href="#builds">Builds</a> ·
      <a href="#tests">Tests</a>
    </td>
    <td width="50%" valign="top">
      <strong>Ship</strong><br>
      <a href="#toolchains">Toolchains</a> ·
      <a href="#frameworks">Frameworks</a> ·
      <a href="#packaging">Packaging</a> ·
      <a href="#publishing">Publishing</a>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <strong>Operate</strong><br>
      <a href="#quality">Quality</a> ·
      <a href="#enterprise">Enterprise</a> ·
      <a href="#distribution">Distribution</a> ·
      <a href="#platforms">Platforms</a>
    </td>
    <td width="50%" valign="top">
      <strong>Inspect</strong><br>
      <a href="#insight">Insight</a> ·
      <a href="#boundaries">Boundaries</a> ·
      <a href="#more">More</a>
    </td>
  </tr>
</table>

<a id="cli"></a>
<details>
<summary><strong>CLI</strong> — human output, JSON, plans, dry runs, and next steps</summary>

`zolt --list` · `zolt help` · `zolt doctor` · `zolt plan`

- One native binary with fast startup.
- Commands grouped by job.
- Human-readable output by default and JSON for automation.
- Actionable failures with the problem, relevant file, and next step.
- Typed build, test, package, native, and CI plans before execution.
- Non-mutating previews for updates, package contents, publishing, and cache pruning.
- Consistent workspace selection and automation controls.
- Project-defined aliases and tasks.

</details>

<a id="projects"></a>
<details>
<summary><strong>Projects</strong> — scaffold, configure, build, run, and manage workspaces</summary>

`zolt init` · `zolt tasks` · `zolt aliases` · `zolt clean`

- Create a Java project or workspace.
- Keep the project model in `zolt.toml`.
- Build, test, package, run, check, clean, and publish selected workspace members.
- Schedule workspace members in dependency order.
- Use workspace dependencies without publishing intermediate artifacts.
- Configure tasks, aliases, sources, resources, generated sources, and integration-test roots.

</details>

<a id="dependencies"></a>
<details>
<summary><strong>Dependencies</strong> — add, update, lock, inspect, and verify</summary>

`zolt add` · `zolt remove` · `zolt platform` · `zolt resolve` ·
`zolt outdated` · `zolt update` · `zolt tree` · `zolt why` ·
`zolt conflicts`

- Compile, runtime, provided, development, test, and annotation-processor scopes.
- Maven BOMs, shared version aliases, exclusions, optional dependencies, classifiers,
  artifact types, and strict constraints.
- Deterministic `zolt.lock` files with SHA-256 integrity.
- Locked and offline builds.
- Dependency graph, selection, policy, and classpath inspection.
- Authenticated Maven-compatible repositories and local repository overlays.

</details>

<a id="builds"></a>
<details>
<summary><strong>Builds</strong> — compile, run, package, and cache</summary>

`zolt build` · `zolt run` · `zolt package` · `zolt run-package` · `zolt cache`

- Compile Java sources and copy or filter resources.
- Run applications directly from the project model.
- Inspect typed build and package plans without executing them.
- Reuse local outputs through a content-addressed cache.
- Share outputs through an optional remote cache.
- Tie cache inputs to sources, dependencies, configuration, and the resolved JDK.

</details>

<a id="tests"></a>
<details>
<summary><strong>Tests</strong> — select, shard, profile, report, and measure coverage</summary>

`zolt test` · `zolt integration-test` · `zolt test plan` · `zolt coverage`

- JUnit Platform, JUnit Vintage, Groovy, and Spock.
- Separate unit and integration-test roots.
- Class, method, pattern, suite, and JUnit tag selection.
- Deterministic shards and profiling-based worker balancing.
- JUnit XML reports and test profile JSON.
- JaCoCo HTML, XML, and execution-data reports.
- Line, branch, instruction, and method coverage floors.
- Separate build and test-runtime JDKs.

</details>

<a id="toolchains"></a>
<details>
<summary><strong>Toolchains</strong> — manage JDKs, shims, and native-image</summary>

`zolt toolchain` · `zolt exec` · `zolt shims` · `zolt native`

- Eclipse Temurin and GraalVM Community JDKs.
- Project-pinned and user-global JDKs.
- `prefer-managed`, `require-managed`, and `allow-system` policy.
- Commands run inside the resolved JDK.
- Opt-in `java`, `javac`, `jar`, `javadoc`, `jshell`, and `native-image` shims.
- GraalVM Native Image builds.

</details>

<a id="frameworks"></a>
<details>
<summary><strong>Frameworks</strong> — Spring Boot, Quarkus, Micronaut, Vert.x, OpenAPI, and Protobuf</summary>

`zolt quarkus plan` · `zolt quarkus test-plan`

- Spring Boot application and WAR packaging.
- Quarkus fast-jar packaging and augmentation plans.
- Documented Micronaut and Vert.x application examples.
- OpenAPI and Protobuf generated Java sources.
- Declared process or JVM generation steps with typed inputs, outputs, ordering,
  and cache policy.
- Generated roots exported through the IDE model.

</details>

<a id="packaging"></a>
<details>
<summary><strong>Packaging</strong> — thin, uber, WAR, framework, BOM, sources, and Javadoc</summary>

`zolt package` · `zolt package --plan` · `zolt run-package`

- Thin jars with a verified runtime-classpath sidecar.
- Deterministic uber jars with explicit duplicate handling.
- Plain WAR, Spring Boot, Spring Boot WAR, and Quarkus packages.
- BOM-only workspace members.
- Sources and Javadoc jars.
- Manifest entries and Maven package metadata.
- Package content plans before archive creation.

</details>

<a id="publishing"></a>
<details>
<summary><strong>Publishing</strong> — Maven repositories, Central, signing, SBOMs, and releases</summary>

`zolt publish` · `zolt release-archive` · `zolt release-verify`

- Maven-compatible repositories and the Sonatype Central Portal.
- Maven Central readiness checks without uploading.
- Checksums, GPG signatures, sources, Javadoc, and CycloneDX SBOMs.
- Dependency-ordered workspace families and atomic Central bundles.
- Full upload preflight before the first request.
- Safe resume for interrupted plain-repository publishes.
- Native release archives verified through real workflows.

</details>

<a id="quality"></a>
<details>
<summary><strong>Quality</strong> — CI checks, policy, evidence, and offline readiness</summary>

`zolt check` · `zolt sbom` · `zolt licenses`

- Local and CI quality contexts.
- Test report, coverage, package, publish, and offline-readiness requirements.
- CycloneDX SBOMs generated from the locked graph.
- Offline dependency license reports.
- Dependency and license policy.
- Stable JSON output and independent progress, color, summary, diagnostic, and timing controls.

</details>

<a id="insight"></a>
<details>
<summary><strong>Insight</strong> — IDE models, plans, dependency evidence, and migration audits</summary>

`zolt plan` · `zolt ide model` · `zolt explain` · `zolt why`

- Single-project and workspace IDE models as JSON.
- Lockfile freshness checks without rewriting.
- Maven and Gradle migration audits, scorecards, and blockers.
- Draft `zolt.toml` generation from supported Maven and Gradle inputs.
- Incumbent build comparison.
- Dependency, classpath, package, test, and framework plans.

</details>

<a id="enterprise"></a>
<details>
<summary><strong>Enterprise</strong> — proxies, private CAs, credentials, mirrors, and isolated state</summary>

`zolt config show`

- HTTP and HTTPS proxies, including authenticated proxies.
- Private certificate authorities without replacing the JDK trust store.
- Basic credentials and bearer tokens referenced through environment variables.
- User-global state redirected with `ZOLT_USER_HOME`.
- Java toolchain mirrors.
- Secrets kept out of project configuration, lockfiles, fingerprints, plans, and logs.

</details>

<a id="distribution"></a>
<details>
<summary><strong>Distribution</strong> — install, update, roll back, release, and self-host</summary>

`zolt self` · `zolt self-check` · `zolt self-parity` · `zolt native-smoke`

- Installer-managed native Zolt versions and release channels.
- Install, select, execute, prune, update, and roll back.
- Zolt builds of Zolt.
- Bootstrap and self-hosted artifact comparison.
- Native binary smoke tests against real project and release workflows.

</details>

<a id="platforms"></a>
<details>
<summary><strong>Platforms</strong> — Linux and macOS native releases</summary>

- Linux x64
- Linux arm64
- macOS arm64
- macOS x64

Windows x64 is experimental and is not yet a supported host.

</details>

<a id="boundaries"></a>
<details>
<summary><strong>Boundaries</strong> — Java-focused and reproducible by design</summary>

Dynamic versions, version ranges, remote SNAPSHOT dependencies, and arbitrary
build-plugin behavior are outside Zolt's reproducible model. Use `zolt explain`,
`zolt plan`, and the [examples](./examples/) to check a project's fit.

</details>

<a id="more"></a>
## More

- [Usage](./USAGE.md)
- [Examples](./examples/)
- [Benchmarks](./docs/benchmarks/)
- [Security](./SECURITY.md)
