# Zolt Manifest Language — Final Design and Implementation Contract

- **Status:** Final; re-reviewed against current main and approved for implementation
- **Target:** First public `0.1.0` release candidate
- **Scope:** The complete authored `zolt.toml` language, canonical output, workspace discovery, source-safe mutation, CLI alignment, and release gates
- **Repository baseline:** `zoltsh/zolt` main at `887a5ce1ef4e1f30ca39bc587dff67dff160615a`

## Thesis

`zolt.toml` is a small, strict build manifest—not a serialized Java object, not unrestricted TOML, and not a plugin configuration dumping ground.

It should feel calm when a project is simple and remain coherent when a project is advanced. The common path stays short because defaults are behavior, not boilerplate. Advanced configuration grows in named domains rather than compound-key soup.

The governing rule is:

> **Use structure to remove repeated context, but add a table only when it owns a real concept. Emit no syntax that Zolt cannot read, explain, and edit safely.**

This document freezes the first public language. After the first release candidate, existing paths and meanings change only through an explicit breaking-version decision; ordinary evolution is additive and must preserve the simplicity of manifests that do not use the new capability.

The language has five non-negotiable qualities:

1. **Sparse:** ordinary projects omit conventional defaults.
2. **Consistent:** field names, table names, local IDs, and symbolic values each have one deliberate lexical role.
3. **Structured:** related concepts live together, with a strict depth budget.
4. **Deterministic:** paths, workspace discovery, ordering, defaults, and source mutation behave identically across platforms.
5. **Safe:** a failed or concurrent edit cannot damage `zolt.toml` or the authoritative `zolt.lock`.

## Latest-main re-review

This contract was re-reviewed on August 19, 2026 against `zoltsh/zolt` main at `887a5ce1ef4e1f30ca39bc587dff67dff160615a`, 65 commits after the previous reviewed baseline `647ef14d5a65700682ce097e5f1ad5c417af90da`.

Current main still uses the pre-cut manifest language—flat workspace member arrays, `defaultMembers`, explicit Maven Central, string Java releases, and the existing compound policy sections. This document therefore remains the one-hard-cut implementation target rather than a description of syntax already shipped.

The intervening implementation work did not require a new flagship syntax. It strengthened seven boundaries that the final contract now preserves explicitly:

1. **Exact update automation:** stable opaque target IDs, canonical manifest-relative paths, schema-v2 discovery, and transactional exact writes.
2. **Workspace ownership:** root policy, member source locations, and the authoritative root lock are revalidated together before an update commits.
3. **Supply-chain policy:** SPDX expressions and reviewed, exact-coordinate license exceptions are now first-class semantics.
4. **Lock and cache integrity:** executable locks use content-addressed artifact paths and exact SHA-256 verification across build, IDE, toolchain, package, and workspace consumers.
5. **Managed toolchains:** an authored Java feature release resolves dynamically to one exact vendor archive whose platform identity and digest are locked.
6. **Subprocess supervision:** tasks, generated tools, Java processes, and native-image execution use one cancellation-aware child-tree supervisor.
7. **Native output ownership:** native inputs and outputs are preflighted, staged privately, and the final executable is published atomically without destroying the previous good binary on failure.

These are implementation guarantees beneath the same sparse authored language. They should not leak new cache paths, transaction knobs, process-heartbeat settings, or vendor archive details into `zolt.toml`.

---

# 1. Final decisions

1. **Fixed field names use lower camel case. Fixed table segments, local IDs, and Zolt-owned symbolic values use lowercase kebab-case.** Single-word names remain lowercase; references and external values preserve the spelling of the thing they name.
2. **The CLI uses kebab case.** Shell syntax is a separate language boundary.
3. **Every workspace declares an explicit `[workspace] name`.** Workspace identity never depends on a directory basename.
4. **Workspace membership lives under `[workspace.members]` using `default`, `include`, and `exclude`.**
5. **Workspace project defaults live under `[workspace.project]`.** Only `group`, `version`, `java`, and `license` may be shared this way; this is not general parent-POM inheritance.
6. **Workspace patterns use a deliberately tiny grammar.** `*` matches exactly one directory segment; no `**`, partial wildcards, character classes, braces, parent traversal, or symlink traversal.
7. **Exclusions filter directory candidates before member manifests are parsed.** An intentionally excluded invalid project does not poison discovery.
8. **A remaining glob candidate becomes a member only when it contains a valid project `zolt.toml`.** Results are normalized, deduplicated, and sorted deterministically.
9. **The array form of `default` accepts exact member paths only.** When `default` is present, a new glob-matched member never joins that explicit selection; omitting `default` deliberately selects all current and future discovered members.
10. **Conventional Java source, resource, and output paths are implicit.**
11. **For a compilable project, `project.java` is an integer Java feature release and the sole compilation target.** BOM projects have no Java target, and there is no second compiler release field.
12. **The managed or host JDK is selected under `[toolchain.java]`.** A managed toolchain version defaults to the effective project Java release.
13. **Maven Central is the dependency-repository default.** Custom repositories are additive; Central changes only through explicit `central = false` or a replacement URL.
14. **Repository order is data, never incidental TOML table order.** An optional `order` array owns nondefault precedence.
15. **A workspace root owns dependency repositories for the entire workspace.** Members do not create per-member dependency repository universes.
16. **Dependency scopes live under `[dependencies.*]`.** The base `[dependencies]` table is implementation scope and `[dependencies.api]` is the exported API boundary.
17. **The dependency lane remains explicit through resolution, lockfiles, workspace projection, classpaths, SBOMs, IDE models, and Maven publication.** API and implementation are not flattened into one compile scope.
18. **Managed and workspace dependencies are explicit.** Use `{ managed = true }` and `{ workspace = true }`; an empty table has no hidden meaning.
19. **Every mutable dependency, platform, version, constraint, BOM-version, and BOM-import entry occupies one physical line.** Multiline values and long-form dynamic subtables are rejected until the source editor supports complete multiline spans.
20. **Project publication identity belongs to `[project]`, not the package-construction domain.** A normal SPDX license is one `license` field.
21. **Credentials are a first-class shared collection under `[credentials.<id>]`.** Dependency and publication repositories reference them by ID.
22. **Optional feature tables are present only when configured and contain a meaningful field.** Zolt does not emit `enabled = true` or empty-table boilerplate.
23. **Canonical table depth is at most three segments.** Three segments are reserved for a real nested domain or a named item.
24. **There is no public migration or compatibility dialect.** Zolt has no external manifest users yet; the implementation makes one hard cut and ships only the final language.
25. **The final repository contains no old parser aliases or dual-language bootstrap path.** The hard cut is built with a pinned pre-cut Zolt binary, not a compatibility parser.
26. **There is no manifest schema-version field.** The installed Zolt version owns the language contract; `[toolchain.zolt] version = "..."` may pin the tool when desired.
27. **A general source formatter is not required for `0.1.0`.** `init` and canonical writers emit the final format; ordinary edits preserve source. A comment-preserving formatter may be added later without changing the language.
28. **Every shared named value has one authoritative source location.** A member may add a new version alias, credential, or platform ID, but may not redeclare a root-owned ID even with an identical value.
29. **Scoped license exceptions are exact, reviewed policy objects.** They live under `[dependencies.license-exceptions.<coordinate>]`, cannot override global deny, and must be exercised rather than becoming stale suppression debt.
30. **Executable lockfiles are content-addressed evidence.** Artifact paths are cache-relative SHA-256 locations paired with exact digests; consumers verify bytes before use and never trust an absolute or repository-layout cache path.
31. **Exact dependency updates use opaque machine target IDs.** Callers discover IDs from schema-v2 outdated output and never construct or decode them; a staged resolve and workspace-wide compare-and-set prove the write.
32. **Managed Java requests lock exact archives.** The manifest names only a concrete feature release, distribution, features, and policy; vendor release URL, platform, archive identity, and digest belong in `zolt.lock`.
33. **Every Zolt-owned subprocess is supervised as a process tree.** Cancellation, timeout, interruption, and parent death terminate descendants; output handling is bounded and primary failures are never hidden by cleanup failures.
34. **Native binaries are published transactionally.** Zolt validates output ownership before mutation, stages on the destination filesystem, verifies an executable candidate, and atomically replaces the prior binary only after success.
35. **Package-mode CLI overrides are previews, not hidden configuration.** A standalone override is allowed only when it does not change resolution inputs; workspace or resolution-changing mode changes require an authored manifest edit and fresh lock.
36. **Portable workspace member-path comparison is frozen to Unicode 17.0.0 data.** It uses NFC plus full default case folding from checked-in, checksummed tables rather than host-JDK, filesystem, or locale behavior.
37. **`zolt workspace members` JSON is a versioned machine contract.** Schema version 1 has a closed, deterministic, workspace-relative shape and is selected explicitly with `--schema-version 1` for automation.
38. **`zolt init --workspace --all-members` is the sole initializer opt-in to dynamic-all membership.** Without it, workspace initialization writes an exact `default` selection.
39. **Manifest configuration inspection requires an explicit view.** `zolt config show` accepts exactly one of `--manifest` and `--effective`; the pre-cut user-global `config show` behavior is removed rather than retained under an alias.
40. **Lockfile version 7 stores authored dependency lanes separately from resolved scopes.** Member-qualified dependency roots retain `api` versus `implementation` even when both resolve through `compile`; no v6 fact is guessed into a v7 lane.

---

# 2. Language laws

## 2.1 Namespaces, fields, and symbolic values

Zolt uses one visual grammar for **names** and another for **fields**.

Fixed table path segments and local IDs use lowercase kebab-case:

```toml
[framework.spring-boot]

[dependencies.test-processor]

[generated.tools.openapi]

[tasks.release-notes]
```

Fixed field names use lower camel case:

```toml
versionRef = "spring-boot"
jvmArgs = ["-Xmx2g"]
developerConnection = "scm:git:ssh://git@example.test/repo.git"
passphraseEnv = "ZOLT_SIGNING_PASSPHRASE"
```

Zolt-owned symbolic values also use lowercase kebab-case:

```toml
mode = "spring-boot-war"
policy = "require-managed"
features = ["native-image"]
produces = "java-sources"
```

Version aliases and other local IDs use the same naming style as table names:

```toml
[versions]
spring-boot = "4.0.6"

[platforms]
"org.springframework.boot:spring-boot-dependencies" = { versionRef = "spring-boot" }
```

This is a grammatical split, not arbitrary case mixing:

- a table segment or local ID names a domain, resource, or declared object;
- a field name identifies a property of that object;
- a symbolic string selects one value from a closed vocabulary;
- a reference preserves the exact spelling of the referenced local ID;
- external strings retain their domain spelling.

The result is intentionally readable in both directions:

```toml
[framework.spring-boot]
native = true

[generated.main.public-api]
kind = "openapi"
produces = "java-sources"
```

Zolt never emits Java-looking symbolic values such as `requireManaged`, `springBootWar`, or `nativeImage`, and it never emits camel-case table names such as `[framework.springBoot]`.

External values are not normalized merely for style. Maven coordinates, artifact IDs, paths, URLs, SPDX expressions, environment-variable names, Java classes, CLI arguments, third-party options, and Maven artifact types retain their native spelling:

```toml
"org.slf4j:slf4j-api" = "2.0.17"
license = "Apache-2.0"
main = "com.example.Main"
tokenEnv = "GITHUB_TOKEN"
type = "test-jar"
```

### Initialisms and brands

Field names treat an initialism as a word:

```text
jvmArgs
apiPackage
keyId
baseUrl
```

Table names, local IDs, and symbolic values use their lowercase domain token:

```text
openapi
spring-boot
graalvm-community
native-image
```

A brand that is conventionally one token stays one token (`openapi`, `junit`, `slf4j`). A compound product or concept uses hyphens (`spring-boot`, `graalvm-community`, `test-processor`).

Do not use `openAPI`, `JVMArgs`, `api_package`, `requireManaged`, `graalvmCommunity`, `nativeImage`, or `[framework.springBoot]`.

## 2.2 CLI casing

The CLI remains conventional kebab case:

```console
zolt integration-test
zolt add --scope test org.junit.jupiter:junit-jupiter:5.13.4
zolt versions set spring-boot 4.0.6
zolt toolchain global status
```

Manifest field names remain lower camel case, while referenced IDs remain kebab-case:

```toml
versionRef = "spring-boot"
```

Other authored Zolt TOML files, including user-global configuration, reuse the same field names and symbolic tokens for shared concepts. Zolt does not maintain a second spelling of toolchain policies or distributions outside `zolt.toml`. Generated files such as `zolt.lock` have their own versioned machine schema.

## 2.3 Naming grammar

- Configuration objects are singular nouns: `project`, `workspace`, `build`, `compiler`, `package`, `native`, `coverage`, `publish`.
- Collections are plural nouns: `members`, `versions`, `credentials`, `repositories`, `dependencies`, `tasks`, `aliases`, `developers`, `tools`, `suites`.
- Scope table segments are short nouns: `api`, `runtime`, `provided`, `dev`, `test`, `processor`, `test-processor`.
- Boolean names are positive: `optional`, `reproducible`, `validateSpec`.
- Units appear where a bare number would be ambiguous: `timeoutSeconds`.
- Environment references end in `Env`: `tokenEnv`, `passwordEnv`, `passphraseEnv`.
- Parent context is not repeated in child keys.

Good:

```toml
[native]
name = "hello"
```

Not:

```toml
[native]
imageName = "hello"
```

Atomic concepts remain leaves when a table would add ceremony:

```toml
[test.runtime]
jvmArgs = ["-Xmx2g"]
```

Not:

```toml
[test.runtime.jvm]
args = ["-Xmx2g"]
```

## 2.4 No interpolation language

`zolt.toml` does not have ambient string interpolation.

Unsupported:

```toml
version = "${VERSION}"
output = "$BUILD_DIR/classes"
url = "https://${HOST}/maven"
```

Environment and project values enter configuration only through explicit typed fields such as `tokenEnv`, `passwordEnv`, resource tokens, and generated-step environment mappings. This keeps fingerprints and diagnostics honest.

## 2.5 Strict TOML dialect

Zolt accepts TOML syntax generally, with one required source shape for sections that public commands edit. Equivalent TOML is not automatically equivalent Zolt manifest source.

For source-mutated dynamic maps, the canonical and accepted shape is:

```toml
[dependencies]
"org.slf4j:slf4j-api" = "2.0.17"
```

The following equivalent TOML shapes are intentionally rejected:

```toml
dependencies."org.slf4j:slf4j-api" = "2.0.17"
```

```toml
[dependencies."org.slf4j:slf4j-api"]
version = "2.0.17"
```

A strict source shape is part of the safety contract, not an arbitrary parser limitation.

## 2.6 Project-relative path grammar

Unless a field explicitly says otherwise, an authored filesystem path is relative to the project that owns the field. Workspace-root fields are relative to the workspace root.

All authored paths:

- use `/` separators on every platform;
- are nonempty and not absolute;
- contain no empty, `.` or `..` segments;
- contain no NUL or control characters;
- are normalized to Unicode NFC for comparison and deterministic output;
- must remain under the owning root after real-path resolution when accessed.

A symlink may be followed only when the resolved target remains under the owning root and the specific domain does not prohibit symlinks. Workspace membership is stricter and rejects symlinked candidates entirely. Output writers validate every existing parent before replacement so a symlink cannot redirect generated files outside the project.

Fields described as paths never expand `~`, environment variables, or URI syntax. Fields described as globs use the domain-specific grammar stated in their section; a glob is not a general path.

## 2.7 Environment-variable names

Every environment reference and configured environment-map key uses the portable grammar `[A-Za-z_][A-Za-z0-9_]*`. Names preserve authored case and are looked up exactly on case-sensitive platforms. Two names that differ only by ASCII case are rejected in the same effective environment map so behavior cannot change on Windows. Empty, non-ASCII, whitespace, `=`, NUL, and control characters are rejected.

---

# 3. Structural budget

## 3.1 Maximum depth

Canonical table paths use at most three segments:

```toml
[workspace.members]                 # 2
[compiler.test]                     # 2
[test.suites.smoke]                 # 3
[publish.repositories.internal]     # 3
[generated.main.public-api]          # 3
[project.developers.ada]            # 3
```

A fourth segment is not introduced merely to classify a named item. Put the discriminator in the item:

```toml
[generated.presets.spring-client]
kind = "openapi"
```

Not:

```toml
[generated.presets.openapi.spring-client]
```

## 3.2 A table must have a job

A table is justified only when at least one is true:

1. It owns a real subdomain with multiple related settings.
2. It contains a dynamic named collection.
3. It removes repeated context from several sibling fields.
4. Its presence has meaningful feature semantics and it contains at least one meaningful field.

Examples:

- `[workspace.members]` is justified: it owns inclusion, exclusion, and default selection.
- `[compiler.test]` is justified: it owns test-compiler overrides.
- `[coverage.minimum]` is not justified: `[coverage] line` and `branch` are already clear.
- `[publish.routes]` is not justified: `release` and `snapshot` are already clear inside `[publish]`.

## 3.3 No empty-table behavior

An empty table never means “enabled,” “managed,” or “default.” Empty option and feature tables are invalid. Empty collection tables such as `[dependencies]`, `[versions]`, or `[bom.versions]` are allowed and mean an empty collection; canonical generation omits them, but a source-preserving edit may retain one to avoid destroying comments or deliberate placement.

Invalid:

```toml
[framework.spring-boot]
```

```toml
[dependencies]
"org.example:library" = {}
```

Use a meaningful field:

```toml
[framework.spring-boot]
native = true
```

```toml
[dependencies]
"org.example:library" = { managed = true }
```

---

# 4. Document kinds and workspace sharing

## 4.1 Standalone project manifest

A standalone project contains `[project]` and no workspace membership:

```toml
[project]
name = "hello"
version = "0.1.0"
group = "com.example"
java = 21
```

`name`, `version`, and `group` are required because no workspace context can supply them. `java` is additionally required for every compilable project. A BOM has no compilation target and omits `java`.

## 4.2 Virtual workspace manifest

A virtual workspace contains `[workspace]` and `[workspace.members]` but no root `[project]`:

```toml
[workspace]
name = "platform"

[workspace.members]
default = ["apps/api"]
include = ["apps/*", "modules/*"]
```

The workspace name is required, stable across directory moves, and uses Zolt's local-ID grammar.

## 4.3 Workspace project defaults

A workspace may remove repeated member identity boilerplate with a deliberately narrow project-default table:

```toml
[workspace.project]
group = "com.example"
version = "1.4.0"
java = 21
license = "Apache-2.0"
```

Only these fields are accepted:

- `group`;
- `version`;
- `java`;
- `license`.

A member always declares its own `project.name`. Missing member fields inherit from `[workspace.project]`; explicitly authored member values win. The shared `java` value applies only to compilable members—BOM members do not consume a Java target. `main`, description, URLs, developers, packaging, dependencies, and every build setting remain project-local.

This gives a normal member the compact form:

```toml
[project]
name = "orders-core"
```

A member manifest copied outside the workspace must supply the now-missing required fields before it becomes a standalone project.

## 4.4 Root-project workspace

A root project and workspace may share one manifest:

```toml
[workspace]
name = "platform"

[workspace.members]
default = ["."]
include = [".", "modules/*"]

[workspace.project]
group = "com.example"
version = "1.4.0"
java = 21

[project]
name = "platform-root"
```

The root project is a workspace member only when exact path `.` appears in `include`. It inherits `[workspace.project]` exactly as any other member does.

## 4.5 Shared root configuration

A workspace root may provide a deliberately limited set of shared configuration:

- `[workspace.project]`;
- `[versions]`;
- `[repositories]` and `[repositories.<id>]`;
- `[credentials.<id>]`;
- `[platforms]`;
- `[toolchain.zolt]`, `[toolchain.java]`, and `[toolchain.java.test]`;
- `[coverage]`;
- `[tasks.<id>]` and `[aliases]`.

Merge rules are explicit.

### Project defaults

`group`, `version`, `java`, and `license` inherit only when absent from the member `[project]`. The effective value records whether it came from the member or workspace root. Name never inherits.

### Named maps

Workspace versions, credentials, and platforms are available to every member. Members may add IDs that do not exist at the root. A root-owned ID may not be redeclared in a member, even with an identical value. One logical value therefore has one source location, one source span, and one exact-update target.

### Dependency repositories

The workspace root owns the dependency repository universe and order. `[repositories]` or `[repositories.<id>]` in a member manifest is invalid. Publication repositories remain project-local under `[publish.repositories.<id>]`.

### Java toolchains

A root `[toolchain.java]` is the member default. A member may replace the main request with its own complete `[toolchain.java]`; no field-by-field merge occurs between two main requests. A member may independently declare `[toolchain.java.test]` while inheriting the root main request. A root `[toolchain.zolt]` is authoritative; member Zolt pins are rejected.

### Coverage

Root coverage floors are workspace minimums. A member may raise a floor but cannot lower it. The effective floor is the numeric maximum for each metric.

### Project-only configuration

Dependencies, dependency policy, build layout, compiler settings, resources, tests, generated sources, packaging, frameworks, native-image settings, BOM settings, and publication settings belong to a project. They are invalid in a virtual workspace root. When a root `[project]` exists, those sections configure that root project only.

### Tasks and aliases

Root tasks and aliases are workspace commands and are available from every member. Member tasks and aliases are local additions. Root and member command IDs share one namespace; collisions fail rather than shadow.

A root task's `cwd` is workspace-relative. A member task's `cwd` is member-relative.

### Command discovery

Any command started inside a discovered workspace member evaluates that member with the workspace root's shared configuration, even when `--workspace` is not supplied. `--workspace` controls member selection; it does not turn root defaults on or off.

A workspace has exactly one authoritative lockfile at the workspace root. Member commands project their selected graph from that root lock. No command creates or consumes a member-local `zolt.lock`. A resolving command started from a member updates the complete authoritative workspace snapshot.

This is a closed list, not a general parent-POM inheritance system. A new shared domain requires an explicit language decision.

---

# 5. Sparse manifests and canonical output

## 5.1 Defaults are behavior, not boilerplate

Canonical writers omit:

- Maven Central when it is the only dependency repository;
- `src/main/java`;
- `src/test/java`;
- `src/main/resources`;
- `src/test/resources`;
- conventional integration-test roots;
- `target` and its conventional output directories;
- default `jar` packaging;
- UTF-8 compiler encoding;
- false booleans;
- empty collections;
- default policy values;
- `language = "java"` while Java is the sole supported language;
- inherited workspace project values;
- managed toolchain values derived from the effective project Java release;
- `required = true` and other true-by-default generated-step fields.

Effective behavior and provenance remain inspectable:

```console
zolt config show --effective
```

## 5.2 Size targets

These are product gates, not parser limits:

- A new standalone application: no more than 10 nonblank manifest lines before optional dependencies.
- A new virtual workspace: no more than 6 nonblank lines before optional project defaults or policy.
- A conventional workspace member using `[workspace.project]`: one `[project]` header and one `name` assignment before dependencies.
- Zolt's own root workspace: one screen.
- Canonical output never expands implicit or inherited defaults.

## 5.3 Canonical field order

Static fields follow schema-defined semantic order, never alphabetic order:

1. identity;
2. primary behavior or selection;
3. paths;
4. optional flags;
5. collections.

The workspace member table is deliberately ordered:

```toml
[workspace.members]
default = ["apps/zolt"]
include = ["apps/*", "modules/*"]
exclude = ["modules/experimental"]
```

`default` stays visually attached to the heading rather than stranded below a long member list.

## 5.4 Canonical top-level order

When present, sections are emitted in this broad order:

1. workspace identity, membership, and project defaults;
2. project identity and project metadata;
3. toolchains;
4. versions, repositories, credentials, and platforms;
5. dependencies and dependency policy;
6. build, compiler, resources, generated sources, tests, and coverage;
7. package, BOM, framework, and native-image settings;
8. publishing;
9. tasks and aliases.

A standalone project naturally begins with `[project]` because no workspace sections exist.

## 5.5 Arrays

- Keep an array inline when the complete assignment fits within 100 columns.
- Otherwise use one item per line, four-space indentation, and a trailing comma.
- The one-line mutable-map rule overrides the width rule.

```toml
include = ["apps/*", "modules/*"]
```

```toml
include = [
    "apps/api",
    "apps/admin",
    "modules/core",
    "modules/http",
]
```

Semantically unordered path and ID arrays are sorted in canonical generated output. Explicit repository `order` is semantic and is preserved exactly.

## 5.6 Dynamic-entry ordering

Canonical generated output sorts semantically unordered dynamic entries by normalized key using Unicode code-point order.

Ordinary source-preserving edits do not reorder user source; additions append to the target table. No TOML table declaration order carries hidden resolution semantics.

## 5.7 Comments and line endings

Ordinary mutations preserve:

- comments;
- table order;
- LF versus CRLF;
- existing quote style for unchanged values;
- unrelated whitespace;
- all unmodified source bytes.

Canonical generation and source-preserving mutation are separate operations. Canonical generated manifests are UTF-8 without a byte-order mark, use LF line endings, and end with exactly one newline. Ordinary source-preserving edits retain the existing LF or CRLF convention and do not normalize unrelated text.

---

# 6. Workspace membership

## 6.1 Canonical form

```toml
[workspace]
name = "zolt"

[workspace.members]
default = ["apps/zolt"]
include = ["apps/*", "modules/*"]
```

With exclusions:

```toml
[workspace]
name = "platform"

[workspace.members]
default = ["apps/api"]
include = ["apps/*", "modules/*"]
exclude = ["modules/experimental"]
```

With explicit members:

```toml
[workspace]
name = "platform"

[workspace.members]
default = ["apps/api"]
include = [
    "apps/api",
    "apps/worker",
    "modules/core",
    "modules/http",
]
```

## 6.2 Fields

### `include`

Required. Contains exact member paths or strict directory patterns.

Every include entry must match at least one final, valid, non-excluded member. Overlapping entries may contribute the same member; they do not need to contribute a unique member. A pattern with no final member is a configuration error and catches misspellings such as `moduels/*`.

### `exclude`

Optional. Contains exact paths or the same limited pattern grammar.

Exclusions filter expanded directory candidates **before** Zolt checks for or parses a member manifest. This lets a workspace deliberately quarantine a matching directory that is not a Zolt project or currently has an invalid manifest.

An exclusion matching no expanded candidate is allowed but reported by `zolt check --workspace` as stale configuration. An exact path cannot appear effectively in both include and exclude; that contradiction is an error.

### `default`

Optional. When authored, it contains exact final member paths only. Patterns are rejected. Every default path must exist in the final member set.

When omitted, a command started at the workspace root deliberately selects all final members unless that command explicitly documents a narrower default. This is the dynamic-all form: future valid members discovered through `include` join default selection. `zolt workspace members` and `zolt config show --effective` identify the selection source as `implicit-all`, so this behavior is never hidden.

`zolt init --workspace` emits an exact `default` selection unless the user explicitly chooses all-members behavior. `zolt check --workspace` reports `implicit-all` when pattern-based membership is combined with an omitted default.

The explicit initializer form is:

```console
zolt init --workspace --all-members NAME
```

`--all-members` is valid only with `--workspace`. It causes the generated `[workspace.members]` table to omit `default`; it does not alter `include`, add a second selection mechanism, or mean that nonmembers are selected. Without `--all-members`, initialization writes `default` with the exact path of every member created by that invocation.

## 6.3 Pattern grammar

A workspace member pattern is a `/`-separated sequence of directory segments. Each segment is either:

- a literal directory name; or
- exactly `*`.

Supported:

```text
apps/*
services/*/api
modules/core
.
```

Rejected:

```text
apps/**
modules/experimental-*
modules/?ore
modules/[ab]*
{apps,modules}/*
../shared
C:\projects\member
apps//api
apps/./api
```

Rules:

- `*` matches exactly one directory segment.
- `*` matches directories only.
- `*` does not match a dot-prefixed directory.
- An exact literal may name a dot-prefixed directory deliberately.
- Backslashes are rejected; manifest paths always use `/`.
- Absolute paths are rejected.
- Empty segments, `..`, and embedded `.` segments are rejected.
- Exact path `.` is the sole special case and means the workspace root project.
- Any symlink in a remaining candidate path is rejected in v1.
- Logical matching is case-sensitive on every operating system.
- Exact literal segments must match the directory entry's actual casing, even on case-insensitive filesystems.
- Logical member paths are normalized to Unicode NFC for comparison, output, and sorting.
- Two raw names that normalize to the same logical path are rejected.
- Two distinct normalized paths that collide under the schema's Unicode case-fold comparison are rejected for cross-platform portability.
- The final canonical relative path uses normalized actual directory-entry casing.
- Pattern order has no semantic effect.
- Duplicate entries inside `include`, `exclude`, or `default` are rejected; overlapping different patterns are allowed.

Zolt implements this grammar directly. It does not delegate to `PathMatcher`, a shell, or platform-native glob behavior.

### Unicode portability key

The schema owns the identifier `unicode-17.0.0-nfc-full-default-case-fold`. Zolt computes the portability key for each logical member path as follows:

1. Reject a path that cannot be represented as a sequence of Unicode scalar values.
2. Normalize the logical `/`-separated path to NFC using Unicode 17.0.0 normalization data.
3. Apply Unicode 17.0.0 **full default case folding** using the `C` and `F` mappings from `CaseFolding.txt`; locale-specific `T` mappings and simple `S` mappings are not used.
4. Normalize the folded result to NFC again with the same Unicode 17.0.0 data.
5. Compare the resulting scalar sequence exactly.

This is not locale-sensitive lowercasing, simple case folding, NFKC, or `NFKC_Casefold`. The original NFC path with actual directory-entry casing remains the canonical displayed and authored path; the folded value is used only to reject portability collisions.

The normalization and case-fold tables, Unicode version identifier, source-file checksums, and generated-table checksum are checked into the repository and verified by tests. Host JDK and operating-system Unicode tables are not semantic inputs. Changing the Unicode data version can change workspace membership validity and therefore requires an explicit breaking language decision after the first RC; it is never an incidental dependency or JDK upgrade.

## 6.4 Candidate expansion

An exact include entry must initially:

1. resolve to a directory beneath the workspace root;
2. use exact actual casing;
3. avoid symlink traversal.

A pattern such as `apps/*`:

1. enumerates immediate child directory entries beneath `apps`;
2. ignores dot-prefixed wildcard matches;
3. creates logical directory candidates at exactly that depth;
4. does not inspect `zolt.toml` yet.

Multi-segment patterns repeat this deterministic directory enumeration at each segment. Unreadable traversed directories are errors with the failing path.

## 6.5 Exclusion and manifest validation

After all include entries expand to directory candidates:

1. Apply every exclusion to candidate logical paths.
2. Reject an exact include that was excluded as contradictory configuration.
3. Ignore a remaining pattern candidate without `zolt.toml`.
4. Require a remaining exact candidate to contain `zolt.toml`.
5. Parse every remaining manifest-bearing candidate.
6. Require `[project]` and reject nested workspace membership.
7. Treat an invalid remaining manifest as an error.
8. Require every include entry to match at least one final valid member after exclusions.

Therefore:

> **`apps/*` means every immediate non-hidden child directory under `apps` that is not excluded and contains a valid Zolt project manifest.**

An invalid `apps/experimental/zolt.toml` is ignored only when `apps/experimental` is explicitly excluded. Without that exclusion, it stops discovery actionably.

## 6.6 Expansion algorithm

Conceptually:

```text
matchesByInclude = ordered evidence map
rawCandidates = normalized path set

for includeEntry in include:
    matches = expandDirectoryCandidates(includeEntry)
    record matchesByInclude[includeEntry] = matches
    add matches to rawCandidates

excludedCandidates = candidates matching any exclude entry
remainingCandidates = rawCandidates - excludedCandidates

reject exact include entries removed by exclusion

members = empty map keyed by normalized logical path
for candidate in remainingCandidates:
    if candidate has no zolt.toml:
        reject if candidate came from an exact include
        otherwise continue
    manifest = parse candidate/zolt.toml
    require valid project and no nested workspace
    add member with include-pattern evidence

for includeEntry in include:
    require includeEntry contributes at least one final member

sort members by normalized Unicode code-point order
require members is not empty
require every default path is an exact member
```

Additional invariants:

- Duplicate matches are deduplicated while retaining all pattern evidence.
- Two logical paths resolving to one real directory are rejected.
- Two member manifests declaring the same effective Maven identity are rejected.
- Final paths use `/`, Unicode NFC, and Unicode code-point ordering.
- Build order comes from workspace dependency edges, never member-list order.
- Excluded candidates are not parsed, fingerprinted, or reported as invalid members.

## 6.7 Newly created directories

A new directory becomes a member only when:

1. its path matches `include`;
2. `exclude` does not remove it;
3. it contains a valid project `zolt.toml`.

Automatic inclusion is intentional and safe because:

- an authored `default` cannot contain a pattern and never changes when a new member is discovered;
- omitting `default` is an explicit dynamic-all choice and is reported as `implicit-all`;
- the final member set is part of workspace lock freshness;
- membership and matching evidence are inspectable;
- invalid newly matched manifests stop the workspace unless explicitly excluded.

Inspection commands:

```console
zolt workspace members
zolt workspace members --format json
zolt workspace members --format json --schema-version 1
```

### Workspace-members JSON schema v1

`--format json` defaults to schema version 1 for interactive use. Automation supplies `--schema-version 1`; `--schema-version` is rejected without `--format json`, and unsupported versions fail before emitting a partial document.

The complete schema-v1 success shape is:

```json
{
  "schemaVersion": 1,
  "workspace": {
    "name": "platform",
    "manifestPath": "zolt.toml",
    "selection": {
      "source": "explicit-default",
      "members": ["apps/api"]
    },
    "members": [
      {
        "path": "apps/api",
        "manifestPath": "apps/api/zolt.toml",
        "projectName": "api",
        "matchedBy": ["apps/*"]
      },
      {
        "path": "modules/core",
        "manifestPath": "modules/core/zolt.toml",
        "projectName": "core",
        "matchedBy": ["modules/*"]
      }
    ]
  }
}
```

`selection.source` is exactly `explicit-default` or `implicit-all`. `selection.members` is the final selected subset; `workspace.members` is the complete final member set. All paths are canonical workspace-relative manifest paths using `/`; the root member uses path `.` and manifest path `zolt.toml`. Members, selected paths, and each `matchedBy` array are sorted by the same Unicode code-point order used by discovery. Pattern evidence is retained after deduplication. The output contains no absolute paths, timestamps, host data, or lock freshness, so identical workspace source produces identical bytes. Field order is the order shown above. Adding, removing, renaming, or retyping a field requires a new schema version.

A newly discovered project makes the authoritative root `zolt.lock` stale:

```text
Workspace membership changed: added modules/newLibrary.
Run `zolt resolve --workspace` and commit zolt.lock.
```

## 6.8 Lock freshness

Workspace lock freshness includes the effective inputs that can change dependency or toolchain resolution:

- final sorted member paths;
- path-to-effective-project coordinate bindings;
- effective member dependency, platform, generated-tool, Java-release, package-consumability, and repository inputs;
- effective shared versions, platforms, repository universe/order, project identity defaults, and toolchain requests;
- exact workspace dependency edges.

Display-only or command-selection data does not stale the lock. Raw `include`, `exclude`, and `default` text does not enter the resolution fingerprint when it expands to the same effective graph. Workspace name, description, license metadata, tasks, aliases, and coverage thresholds do not stale the dependency lock unless a future feature explicitly makes them resolution inputs.

Changing only `default` never requires a lockfile rewrite.

## 6.9 Root member

Exact `.` is allowed:

```toml
[workspace]
name = "root-tools"

[workspace.members]
default = ["."]
include = [".", "modules/*"]

[workspace.project]
group = "com.example"
version = "0.1.0"
java = 21

[project]
name = "root-tool"
```

The root manifest must contain `[project]` when `.` is included.

## 6.10 Zolt's own root

The target root manifest is:

```toml
[workspace]
name = "zolt"

[workspace.members]
default = ["apps/zolt"]
include = ["apps/*", "modules/*"]

[workspace.project]
group = "sh.zolt"
version = "0.1.0-SNAPSHOT"
java = 21
license = "Apache-2.0"

[toolchain.java]
distribution = "graalvm-community"
features = ["native-image"]
policy = "require-managed"

[coverage]
line = 88
branch = 74
```

Every conventional Zolt member can now begin with only:

```toml
[project]
name = "zolt-model"
```

---

# 7. Project identity and publication metadata

## 7.1 Core project

Standalone form:

```toml
[project]
name = "hello"
version = "0.1.0"
group = "com.example"
java = 21
main = "com.example.Main"
```

Workspace member form with root defaults:

```toml
[project]
name = "orders-core"
```

Effective required fields:

- `name` — Maven artifact ID and workspace artifact identity; always authored locally;
- `version` — project package version;
- `group` — Maven group ID;
- `java` — integer Java feature release used by compilation and compatibility checks; required only for compilable projects.

`version` and `group` may come from `[workspace.project]`. `java` may also come from `[workspace.project]` for a compilable member. A BOM does not consume an inherited Java release, and an explicitly authored `project.java` on a BOM is rejected as meaningless. Optional project fields never inherit except `license`.

Optional local fields:

- `main` — application main class;
- `description`;
- `url`;
- `issues`;
- `license`.

A library normally omits `main`.

Project names are Maven artifact IDs and are external domain values, not Zolt local IDs. `name` and `group` use the same portable Maven-safe segment characters as dependency coordinates: ASCII letters, digits, `_`, `.`, and `-`, with no colon, whitespace, or control characters. Zolt recommends lowercase artifact names such as `orders-core` and reverse-domain groups such as `com.example` but does not rewrite them.

Project `version` is one fixed literal package version. A project may use a conventional `-SNAPSHOT` suffix, but ranges, dynamic selectors, interpolation, surrounding whitespace, and incomplete literals are rejected. `main`, when present, is a fully qualified portable ASCII Java binary class name. Every dot-separated segment begins with an ASCII letter, `_`, or `$`; remaining characters may also be ASCII digits. Java 21 reserved words and literals are rejected, while `$` retains its binary-name role for nested, local, and anonymous classes. This deliberately pinned subset avoids ambient-JDK Unicode identifier drift. Optional textual metadata is nonblank when authored.

## 7.2 Java release semantics

For a compilable project, `project.java` is a positive integer Java feature release:

```text
java = 8
java = 17
java = 21
```

Strings, dotted runtime versions, vendor build numbers, and channels are invalid in this field.

Zolt uses the effective project Java release for:

- `javac --release` by default;
- package and toolchain compatibility checks;
- IDE model language level;
- generated metadata.

The selected build JDK belongs under `[toolchain.java]`. There is no `[compiler].release` field. A BOM has no project Java release, compiler target, or project Java toolchain requirement.

## 7.3 License shorthand

The common SPDX form stays in `[project]` or `[workspace.project]`:

```toml
license = "Apache-2.0"
```

The string shorthand is one SPDX license identifier, not an expression. Zolt derives standard Maven license name and URL metadata for a known identifier.

A custom or compound license uses a one-line inline table:

```toml
license = { id = "Apache-2.0 OR MIT", name = "Apache License 2.0 or MIT License", url = "https://example.com/licenses" }
```

Supported inline fields:

- `id` — optional SPDX identifier or expression;
- `name`;
- `url`.

At least `id` or `name` is required. For a known single SPDX identifier, Zolt may derive omitted `name` and `url`. For an SPDX expression, unknown identifier, or custom license, Maven Central readiness requires explicit `name` and `url`; ordinary nonpublishing builds may still retain the authored metadata without claiming Central readiness.

## 7.4 SCM and developers

```toml
[project]
name = "example-library"
version = "1.0.0"
group = "com.example"
java = 21
description = "A reusable Java library."
url = "https://example.com/library"
issues = "https://github.com/example/library/issues"
license = "Apache-2.0"

[project.scm]
url = "https://github.com/example/library"
connection = "scm:git:https://github.com/example/library.git"
developerConnection = "scm:git:ssh://git@github.com/example/library.git"
tag = "v1.0.0"

[project.developers.ada]
name = "Ada Lovelace"
email = "ada@example.com"
organization = "Analytical Engines"
url = "https://example.com/ada"
```

Developer IDs use the local lowercase kebab-case grammar. Developer display names and external values retain their native spelling.

---

# 8. Versions, repositories, credentials, and platforms

## 8.1 Version aliases

```toml
[versions]
junit = "5.13.4"
spring-boot = "4.0.6"
```

Rules:

- Values are fixed literal versions.
- Ranges, interpolation, and dynamic selectors remain unsupported.
- Every assignment occupies one physical line.
- Canonical generated output sorts aliases by key.

## 8.2 Default repository universe

Maven Central is enabled at its canonical HTTPS URL unless explicitly disabled or replaced. This remains true when custom repositories are added, so adding one private source does not silently remove the normal public source.

The common case needs no manifest syntax.

## 8.3 Repository universe controls

Custom repositories are additive by default:

```toml
[repositories.internal]
url = "https://repo.example.com/maven"
credentials = "company"
```

Optional control fields live in `[repositories]`:

- omitted `central` — enable the canonical Maven Central URL;
- `central = true` — the same explicit behavior; canonical output omits it;
- `central = false` — disable Central;
- `central = "https://..."` — replace Central with an unauthenticated mirror URL while retaining the reserved ID `central`;
- `central = { url = "https://...", credentials = "company" }` — replace Central with an authenticated mirror;
- `order` — optionally declare exact lookup order.

The inline Central replacement accepts required `url` and optional `credentials`, using the same URL and credential rules as a named repository. No `[repositories.central]` subtable exists; `central` is the reserved control field.

An explicitly authored empty `[repositories]` table is invalid because it changes nothing. `central = false` with no custom repositories deliberately declares an empty external-repository universe; it is legal only while the effective build needs no external dependency, platform, or JVM tool artifact. The first such request fails with an actionable no-repository diagnostic.

## 8.4 Named repository definitions

Custom dependency repositories are named subtables:

```toml
[repositories.company-releases]
url = "https://repo.example.com/releases"
credentials = "company"
```

Fields:

- required `url`;
- optional `credentials` referencing `[credentials.<id>]`.

Repository IDs use the local lowercase kebab-case grammar. `central` and `order` are reserved and cannot be custom IDs.

Dependency and publication repository URLs must be absolute HTTP(S) URLs with a host and without user information or fragments. Remote repositories require HTTPS; plain HTTP is allowed only for `localhost`, IPv4 loopback, or IPv6 loopback development repositories. Any credentialed remote repository requires HTTPS. Credentials are references, not URL-embedded secrets. A trailing `/` is normalized for identity without changing the authored source.

## 8.5 Lookup order

Repository order is never inferred from TOML table declaration order.

- With no explicit `order`, custom repositories are queried by normalized ID order and enabled Central is last.
- With `order`, the array must list every enabled repository ID exactly once, including `central` when enabled.
- Missing, disabled, duplicated, or unlisted IDs are configuration errors.
- Canonical output omits `order` when it equals the default order.

An authenticated Central mirror is still compact:

```toml
[repositories]
central = { url = "https://repo.example.com/maven-central", credentials = "company" }

[credentials.company]
tokenEnv = "MAVEN_TOKEN"
```

A nondefault order is explicit:

```toml
[repositories]
order = ["snapshots", "releases", "central"]

[repositories.releases]
url = "https://repo.example.com/releases"

[repositories.snapshots]
url = "https://repo.example.com/snapshots"
```

Lockfiles record the selected source so resolution remains auditable.

## 8.6 Credentials

```toml
[credentials.company]
usernameEnv = "MAVEN_USERNAME"
passwordEnv = "MAVEN_PASSWORD"

[credentials.github]
tokenEnv = "GITHUB_TOKEN"
```

A credential declares exactly one authentication form:

- `tokenEnv`; or
- both `usernameEnv` and `passwordEnv`.

`tokenEnv` means HTTP bearer authentication. `usernameEnv` plus `passwordEnv` means HTTP Basic authentication. A repository whose token is used as a Basic-auth password declares the latter form explicitly; Zolt never guesses an authentication scheme from a secret's shape.

Credentials contain environment-variable names, never secret values. Dependency repositories and publication repositories reference the same credential collection.

## 8.7 Workspace repository ownership

A workspace root owns the complete dependency repository universe and order. Member manifests may not declare `[repositories]` or `[repositories.<id>]`.

Root credentials are available to members. Members may add credentials needed by project-local publication or generated tools, but may not redeclare a root-owned credential ID. This keeps authentication provenance and update ownership unambiguous.

This one-universe rule prevents two members in one authoritative lock from resolving the same coordinate through silently different repository precedence.

## 8.8 Platforms

```toml
[platforms]
"org.springframework.boot:spring-boot-dependencies" = { versionRef = "spring-boot" }
"io.netty:netty-bom" = "4.1.119.Final"
```

A platform value is:

- a fixed version string;
- `{ version = "..." }` when metadata requires table form;
- `{ versionRef = "..." }`.

Each platform assignment occupies one physical line.

## 8.9 Repository cache and lockfile integrity

`zolt.lock` is generated public data and the authoritative evidence for selected package, tool, and toolchain bytes. Authored repository configuration selects sources; it never exposes or controls cache layout.

Executable locks use content-addressed cache-relative artifact paths:

```text
blobs/v2/sha256/<64-lowercase-hex-digest>/<artifact-file-name>
```

For every locked JAR, POM, classifier artifact, generated-tool artifact, and managed-toolchain archive:

- a cache path and SHA-256 digest are recorded together or both are absent;
- the digest directory exactly equals the recorded digest;
- paths are relative, normalized, contained beneath the selected cache root, and never include host-specific absolute paths;
- the selected repository/source identity remains locked separately from byte identity;
- every executable consumer verifies the current bytes before using them.

Repository cache scope is derived from canonical repository ID, canonical URI, and authentication mode/reference without including secret values. Identical public bytes may deduplicate through content addressing while source provenance remains auditable. Unauthenticated metadata may be cached under that scope. Authenticated repository metadata is not persisted for offline discovery or used as a stale fallback after a transient authentication/network failure.

A local Maven overlay or other mutable local artifact is snapshotted into immutable content-addressed storage before it becomes locked input. Later changes to the local source do not silently mutate an existing lock.

When cached bytes are missing or corrupt:

- an online operation may repair them only by retrieving bytes that match the exact locked digest;
- an offline operation fails closed and names the artifact and required remediation;
- a path/hash mismatch or malformed content-addressed path is a lockfile-contract failure, not a cache miss;
- build, test, run, package, native, IDE, SBOM, and workspace projections use the same verification boundary.

The hard cut may regenerate the lock schema, but the final implementation retains this content-addressed contract rather than reverting to Maven-layout cache paths.

---

# 9. Dependencies

## 9.1 Scope layout

Implementation dependencies use the base table:

```toml
[dependencies]
"com.fasterxml.jackson.core:jackson-databind" = "2.19.0"
```

Other scopes stay under the dependency concept:

```toml
[dependencies.api]
"com.example:public-contract" = { workspace = true }

[dependencies.runtime]
"ch.qos.logback:logback-classic" = "1.5.18"

[dependencies.provided]
"jakarta.servlet:jakarta.servlet-api" = "6.1.0"

[dependencies.dev]
"org.springframework.boot:spring-boot-devtools" = { managed = true }

[dependencies.test]
"org.junit.jupiter:junit-jupiter" = { versionRef = "junit" }

[dependencies.processor]
"org.mapstruct:mapstruct-processor" = "1.6.3"

[dependencies.test-processor]
"org.projectlombok:lombok" = "1.18.38"
```

Canonical section order is:

1. `dependencies`;
2. `dependencies.api`;
3. `dependencies.runtime`;
4. `dependencies.provided`;
5. `dependencies.dev`;
6. `dependencies.test`;
7. `dependencies.processor`;
8. `dependencies.test-processor`;
9. `dependencies.constraints`;
10. dependency policy.

## 9.2 Scope semantics

The lane is part of the public semantic model and is never flattened away.

| Authored section | Current project | Tests | Downstream workspace consumer | Published Maven scope | Packaged/runtime closure |
| --- | --- | --- | --- | --- | --- |
| `[dependencies.api]` | main compile and run | compile and run | compile and run | `compile` | yes |
| `[dependencies]` | main compile and run | compile and run | runtime only | `runtime` | yes |
| `[dependencies.runtime]` | main run only | compile and run | runtime only | `runtime` | yes |
| `[dependencies.provided]` | main compile only | compile and run | not propagated | `provided` | no |
| `[dependencies.dev]` | development run only | no | not propagated | omitted | no |
| `[dependencies.test]` | no | compile and run | not propagated | omitted | no |
| `[dependencies.processor]` | main annotation-processor path | no | not propagated | omitted | no |
| `[dependencies.test-processor]` | no | test annotation-processor path | not propagated | omitted | no |

Consequences:

- API dependencies are the only dependencies exposed to a downstream member's compile classpath.
- Implementation dependencies remain available to the current project but become runtime dependencies for published consumers.
- Test classpaths include the project's API, implementation, runtime, and provided lanes plus test dependencies.
- Dev dependencies are for interactive/development run workflows only; tests that need them declare them under test.
- Uber and framework package closures include API, implementation, and runtime lanes, never provided, dev, test, or processor lanes.

The lockfile, tree/why output, IDE model, SBOM projection, classpath planner, workspace graph, and POM generator must preserve enough origin information to implement this table exactly.

### Lockfile v7 lane model

The hard cut always writes `version = 7`. It introduces a distinct authored model:

```java
enum DependencyLane {
    API,
    IMPLEMENTATION,
    RUNTIME,
    PROVIDED,
    DEV,
    TEST,
    PROCESSOR,
    TEST_PROCESSOR
}
```

`DependencyLane` answers **where the direct declaration was authored**. `DependencyScope` answers **which resolved graph/classpath role a package node or edge occupies**. They are different types and neither is derived from the other's enum name. In particular, API and implementation roots both resolve initially through `compile`, but only the API lane propagates onto a downstream consumer's compile classpath and publishes as Maven compile scope.

Version 7 adds one member-qualified `[[dependencyRoot]]` record for every direct authored declaration in the eight dependency lanes, including a declaration that resolves to the same package node as another member's declaration:

```toml
version = 7

[[dependencyRoot]]
member = "modules/core"
id = "org.slf4j:slf4j-api"
version = "2.0.17"
lane = "api"
resolvedScope = "compile"
```

The canonical fields are:

- `member`: canonical workspace-relative owning member path, or `.` for a standalone project and a workspace root project;
- `id`: exact `group:artifact` declaration identity;
- `version`: exact selected version, including the effective value behind `versionRef` or `managed = true`;
- optional `variant`: `LockArtifactVariant.key()` in canonical `extension` or `extension|classifier` form, omitted for the default unclassified JAR;
- `lane`: exactly `api`, `implementation`, `runtime`, `provided`, `dev`, `test`, `processor`, or `test-processor`;
- optional `resolvedScope`: the `DependencyScope` lock token of the package node selected by this root;
- optional `optional = true` and `publishOnly = true` declaration facts; absent booleans are false.

Every non-`publishOnly` root has `resolvedScope` and points to the package identity formed by `id`, `version`, `variant`, and that resolved scope. A `publishOnly` root has no resolved package node and therefore omits `resolvedScope`; its exact version and authored lane remain locked for publication metadata. A virtual workspace root has no dependency roots of its own.

`[[package]].scope`, scope-qualified dependency-edge strings, and `[[memberGraph]].scope` remain resolved-scope facts. They never substitute for `dependencyRoot.lane`. Transitive package nodes do not receive a guessed authored lane; their reachability stays represented by resolved edges from the member-qualified roots. The root records are sorted by member path, canonical lane order from §9.1, coordinate, and variant.

All final lock readers reject a version-7 root with a missing or unknown lane, a non-publish-only root without `resolvedScope`, a publish-only root with `resolvedScope`, or a root whose selected package node is absent. Executable lock consumers reject pre-v7 locks. `zolt resolve` may treat a pre-v7 file only as replaceable stale state and rebuild version 7 from current manifests; it never imports, maps, or guesses pre-v7 scope facts into authored lanes. Content-addressed artifact paths, digests, source provenance, member-qualified graph facts, optionality facts, and exact-update identity guarantees remain unchanged.

## 9.3 Coordinate grammar

Dependency, platform, constraint, BOM-version, and BOM-import keys are exact `group:artifact` coordinates. They contain exactly one colon and no version, classifier, type, whitespace, interpolation, or control character. Each group and artifact segment uses the portable Maven-safe grammar `[A-Za-z0-9_.-]+`; Zolt preserves its exact spelling. Artifact variant data belongs in explicit `classifier` and `type` fields.

## 9.4 Value grammar

A dependency declares exactly one source.

### Fixed version shorthand

```toml
"org.slf4j:slf4j-api" = "2.0.17"
```

### Fixed version table

```toml
"com.example:client" = { version = "1.4.0", optional = true }
```

### Version alias

```toml
"org.junit.jupiter:junit-jupiter" = { versionRef = "junit" }
```

### Platform managed

```toml
"org.springframework.boot:spring-boot-starter-web" = { managed = true }
```

### Workspace member

```toml
"com.example:core" = { workspace = true }
```

Exactly one selector is allowed:

- string shorthand or `version`;
- `versionRef`;
- `managed = true`;
- `workspace = true`.

An empty inline table is invalid.

## 9.5 Metadata

Metadata is accepted only where it has observable semantics.

| Field | Allowed selectors | Allowed lanes |
| --- | --- | --- |
| `optional` | `version`, `versionRef`, `managed`, or `workspace` | API, implementation, runtime |
| `publishOnly` | `version` or `versionRef` | API, implementation, runtime, provided |
| `classifier` | `version`, `versionRef`, or `managed` | Any external dependency lane |
| `type` | `version`, `versionRef`, or `managed` | Any external dependency lane |
| `exclude` | `version`, `versionRef`, or `managed` | Any external dependency lane |

```toml
"com.example:client" = { version = "1.4.0", optional = true, exclude = ["commons-logging:commons-logging"] }
```

`exclude` contains exact `group:artifact` coordinates. Wildcards are unsupported. It describes transitive exclusions on one external edge and is valid in ordinary, test, dev, and processor lanes.

`workspace = true` is valid in any dependency lane when the matched member produces a consumable JAR for that lane. A workspace declaration may add `optional = true` only in API, implementation, or runtime. It may not add `publishOnly`, `classifier`, `type`, or `exclude`, because those describe publication-only or external-artifact behavior.

`optional = true` does not remove the dependency from the declaring project. The current project still receives the edge in its authored lane and may package it when that lane is packageable. Optionality suppresses transitive propagation to downstream workspace consumers, is emitted as Maven `<optional>true</optional>`, and is retained separately from path-derived “optional-only” reachability in the lockfile. Optionality is rejected in provided, dev, test, and processor lanes because those lanes already do not propagate.

`publishOnly = true` puts the dependency only in generated publication metadata using the authored lane's Maven scope: API maps to compile, implementation and runtime map to runtime, and provided maps to provided. It is rejected in test, dev, and processor lanes. It never enters compilation, execution, packaging, IDE, test, or SBOM runtime graphs. It is allowed only with `version` or `versionRef`; platform-managed and workspace selectors require a real resolved edge and therefore cannot be publish-only.

Published direct dependencies always carry exact locked versions. A normal `managed = true` edge is resolved through `[platforms]`, then its selected locked version is written to the generated POM. Imported `[platforms]` are build-resolution inputs and are not silently re-exported from an ordinary library POM. Projects that intentionally publish dependency management author a dedicated `[bom]`.

## 9.6 Canonical inline-field order

Dependency inline tables emit fields in this order:

1. `version`, `versionRef`, `managed`, or `workspace`;
2. `optional`;
3. `publishOnly`;
4. `classifier`;
5. `type`;
6. `exclude`.

## 9.7 Variant uniqueness

A dependency variant identity is the exact `group:artifact` coordinate plus normalized `classifier` and `type` (`jar` and no classifier by default).

The same variant may appear in only one ordinary classpath lane across API, implementation, runtime, provided, dev, and test. Repeating an implementation variant under test is redundant because main dependencies already enter the test classpath; a genuinely distinct test artifact uses an explicit classifier or type and therefore has a different variant identity.

Processor and test-processor are separate tool lanes. A variant used there may also appear in an ordinary classpath lane, and the same processor variant may appear in both processor lanes because main and test annotation processing are distinct execution contexts.

Mutation commands move an ordinary variant between lanes rather than leaving conflicting declarations.

## 9.8 Workspace resolution

`workspace = true` resolves by effective project identity:

1. Parse the dependency key as `group:artifact`.
2. Find the unique workspace member whose effective `project.group` and `project.name` match.
3. Use that member's effective version and member-qualified locked graph.
4. Verify the member package mode is consumable as a library.

Errors:

- no matching member;
- duplicate member identity;
- non-library package mode;
- incompatible workspace metadata.

Member paths are not repeated in dependency declarations.

## 9.9 Source-safe physical-line rule

Every entry in these tables occupies one physical line beneath its explicit canonical table header:

- `[versions]`;
- `[platforms]`;
- `[dependencies]` and all dependency scopes;
- `[dependencies.constraints]`;
- `[bom.versions]`;
- `[bom.imports]`.

Dotted assignments, inline parent-table declarations, multiline values, and long-form dynamic subtables are rejected for every table in this list.

Accepted:

```toml
[dependencies]
"com.example:client" = { version = "1.4.0", exclude = ["a:b", "c:d"] }
```

Rejected even though it is valid TOML:

```toml
[dependencies]
"com.example:client" = { version = "1.4.0", exclude = [
    "a:b",
    "c:d",
] }
```

Rejected:

```toml
[dependencies."com.example:client"]
version = "1.4.0"
optional = true
```

Rejected:

```toml
dependencies."com.example:client" = "1.4.0"
```

Diagnostic:

```text
Entries in [dependencies] must use one physical assignment line under the explicit canonical table header.
This source shape is required by Zolt's failure-safe manifest editor.
```

Equivalent diagnostics name `[versions]`, `[platforms]`, `[dependencies.constraints]`, `[bom.versions]`, or `[bom.imports]` and show the exact canonical one-line rewrite. The canonical writer never wraps these entries, even beyond the ordinary line-width target.

## 9.10 Constraints

Constraints are strict exact-version requirements in `0.1.0`.

Fixed shorthand:

```toml
[dependencies.constraints]
"io.netty:netty-handler" = "4.1.119.Final"
```

Alias or reason form:

```toml
[dependencies.constraints]
"io.netty:netty-handler" = { versionRef = "netty", reason = "Security baseline" }
```

A table declares exactly one of `version` or `versionRef`, plus optional `reason`. There is no `kind` field while strict is the only implemented semantic. A future additive constraint mode may introduce one when it has real behavior.

Constraint assignments remain one physical line. Constraints influence the project resolver only; ordinary library POMs do not publish them as dependency management. Publish intentionally reusable version alignment through a dedicated `[bom]`.

## 9.11 Policy

```toml
[dependencies.policy]
conflicts = "fail"
deny = [
    { coordinate = "commons-logging:commons-logging", reason = "Use SLF4J" },
]

[dependencies.policy.licenses]
allow = ["Apache-2.0", "MIT", "Unicode-3.0"]
deny = ["GPL-3.0-only"]
unknown = "fail"

[dependencies.license-exceptions."org.example:matchit"]
allow = ["BSD-3-Clause"]
version = "0.8.4"
reason = "Reviewed transitive dependency; declared as MIT AND BSD-3-Clause"
```

Conflict values:

- `resolve` — deterministic mediation, default and omitted;
- `warn` — resolve and emit a structured warning;
- `fail` — reject any mediated version conflict.

Policy `deny` entries block project-wide direct dependencies and are distinct from per-edge dependency `exclude` metadata.

### License terms and expressions

Global `allow` and `deny` entries are policy terms, not arbitrary compound expressions. A term is either:

- a canonical SPDX license identifier;
- a canonical `LICENSE WITH EXCEPTION` SPDX term; or
- an exact raw label retained for genuinely unmapped Maven evidence.

A valid `AND`/`OR` expression is parsed from dependency evidence rather than placed in the global term lists. Malformed SPDX-shaped values fail closed instead of becoming permissive raw labels. Zolt pins its SPDX license and exception catalogs in source; parsing and evaluation never depend on the network.

Expression evaluation is deterministic:

- `A AND B` requires both branches;
- `A OR B` requires one acceptable branch and selects the best branch deterministically;
- `A WITH E` is one indivisible policy term;
- denying base license `A` also denies every `A WITH E` combination;
- several separate Maven `<license>` records remain publisher-provided alternatives and are not silently rewritten into an asserted SPDX expression.

Global `deny` always wins. An empty `allow` list permits every term not denied; a nonempty `allow` list is authoritative. Unmapped or missing evidence follows `unknown = "allow" | "warn" | "fail"`, with `warn` as the default and therefore omitted.

### Scoped license exceptions

A table under `[dependencies.license-exceptions.<coordinate>]` extends a nonempty global allowlist for one reviewed dependency only. The quoted dynamic key is the exact `group:artifact` identity.

Fields:

- required nonempty `allow` containing canonical SPDX terms only;
- optional exact fixed `version`;
- required nonblank `reason`.

Rules:

- global deny cannot be overridden;
- no wildcard coordinate, classifier, type, version range, raw unknown label, or whole `AND`/`OR` expression is accepted;
- an exception is project/member-local and applies only to that policy owner's enforced closure;
- API, implementation, and runtime dependencies are enforced; provided, test, dev, and tool-only dependencies remain report-only in v1;
- every exception is audited as `used`, `version-mismatched`, `missing`, or `redundant`;
- version-mismatched, missing, and redundant exceptions fail the policy check so dead review debt cannot accumulate;
- dependency update commands never rewrite an exception's `version`, `allow`, or `reason`; changing a reviewed dependency version intentionally creates a version-mismatch that requires a fresh human policy review;
- exception versions are not update targets and do not appear in the exact-update catalog;
- reports preserve the canonical expression, decisive term, matched exception, reviewed version, reason, and owning member.

The separate `[dependencies.license-exceptions.<coordinate>]` namespace keeps canonical table depth at three segments and avoids both a five-segment policy table and a duplicate-prone array of anonymous exceptions.

---

# 10. Build, compiler, resources, tests, and coverage

## 10.1 Build roots

The normal Java layout needs no build table.

Custom main source roots:

```toml
[build]
sources = ["src/main/java", "src/generated/java"]
```

Rules:

- `sources` is the only canonical main-source field.
- The singular `source` form does not exist.
- Paths are project-relative, normalized, and contained beneath the project root.
- Kotlin, Scala, Android, or other unsupported source roots fail actionably rather than being silently ignored.

## 10.2 Output layout

```toml
[build.output]
root = "target"
main = "classes"
test = "test-classes"
integration = "integration-test-classes"
```

Rules:

- `main`, `test`, and `integration` are relative to `root`.
- `[build.output]` is omitted when all values are conventional.
- Integration output is configured here only; `[test.integration]` does not duplicate it.
- Output roots may not be absolute, `.` itself, or escape the project root.

## 10.3 Build metadata

```toml
[build.metadata]
buildInfo = true
git = true
reproducible = true
```

False and default fields are omitted.

## 10.4 Compiler

```toml
[compiler]
encoding = "UTF-8"
jdkApi = "release"
args = ["-Xlint:all"]

[compiler.test]
jdkApi = "host"
args = ["-parameters"]

[compiler.generated]
main = "generated/sources/annotations"
test = "generated/test-sources/annotations"
```

Canonical fields:

### `[compiler]`

- `encoding` — default `UTF-8`, omitted;
- `jdkApi` — `release` by default or `host`;
- `args` — additional main `javac` arguments.

### `[compiler.test]`

- `jdkApi` — inherits main compiler API mode when omitted;
- `args` — additional test `javac` arguments.

### `[compiler.generated]`

- `main`;
- `test`.

Generated compiler paths are relative to `[build.output].root`.

`project.java` owns the release target. Zolt rejects `--release`, source/target flags, encoding flags, output flags, classpath flags, processor-path flags, and generated-source flags inside raw `args` when a first-class field owns the setting.

`jdkApi = "release"` uses the effective project Java release and `ct.sym`. `jdkApi = "host"` intentionally compiles against the selected build JDK's platform API and is an explicit reproducibility escape hatch.

## 10.5 Resources

Conventional resource roots are implicit.

```toml
[resources]
main = ["src/main/resources"]
test = ["src/test/resources"]
```

Resource filtering:

```toml
[resources.filter]
targets = ["main", "test"]
include = ["**/*.properties", "**/*.yaml"]
missing = "fail"
```

Rules:

- Presence enables filtering.
- `include` is required and nonempty; Zolt never guesses which binary or text files should be rewritten.
- `targets` defaults to `["main"]`; supported values are `main` and `test`.
- `missing` defaults to `fail`; supported values are `fail` and `keep`.
- Selected resources must be valid UTF-8 text and contain no NUL byte; selecting a binary file fails actionably.
- Tokens use `@token-name@` syntax.

Resource patterns are root-relative `/`-separated file globs with deterministic Zolt-owned semantics:

- `*` matches zero or more non-`/` characters inside one segment;
- `?` matches one non-`/` character;
- `**` as a complete segment matches zero or more directory segments;
- character classes, braces, backslashes, absolute paths, and parent traversal are rejected;
- matching is case-sensitive on every platform;
- directory traversal does not follow symlinks outside the resource root.

Zolt does not delegate resource pattern semantics to the host filesystem matcher.

Token sources:

```toml
[resources.tokens]
app-version = { project = "version" }
build-id = { env = "BUILD_ID" }
channel = { value = "preview" }
```

Each token declares exactly one of `project`, `env`, or `value`. Supported project fields are `name`, `version`, `group`, `java`, and `main`. Project tokens read effective values; an integer Java release renders as its canonical decimal string.

Token IDs use the local lowercase kebab-case grammar. Literal and project-derived token values enter the resource fingerprint directly. Environment-derived values enter it through a cryptographic digest so the raw value is not written to fingerprints or diagnostics. This is fingerprint hygiene, not a secrecy boundary: the filtered artifact contains the value, and a digest can still reveal equality or permit guessing of low-entropy inputs. Environment-backed resource tokens are therefore for non-secret build metadata; secret-bearing resource interpolation is unsupported in v1. A referenced but unset environment variable fails.

Token entries use one-line inline tables.

When several resource roots contain the same normalized relative path, the build fails and names every source. Root array order is never an implicit overwrite policy. Generated resources join the same collision check unless their declared `into` destinations keep the paths distinct.

## 10.6 Test sources

```toml
[test.sources]
java = ["src/test/java"]
groovy = ["src/test/groovy"]
```

The table is omitted for conventional Java tests.

## 10.7 Test runtime

```toml
[test.runtime]
jvmArgs = ["-Xmx2g"]
properties = { "user.timezone" = "UTC" }
env = { APP_ENV = "test" }
events = ["skipped", "failed"]
```

Environment and property maps contain literal configured values. Secret indirection belongs in a purpose-built secret field, not arbitrary interpolation.

`events` selects the per-test outcomes Zolt prints while the test run is in progress. Supported values are `passed`, `skipped`, and `failed`; the default is empty and therefore omitted. The field is a set: duplicates are rejected, and canonical generated output follows the fixed order `passed`, `skipped`, `failed`. It does not define lifecycle hooks or affect test selection, execution, reports, or exit status.

Values in `jvmArgs`, `properties`, and `env` are literal. The pre-release `${project.root}` placeholder is removed by the hard cut; project-relative paths belong in purpose-built path fields rather than a one-off interpolation escape.

## 10.8 Integration tests

```toml
[test.integration]
sources = ["src/integration-test/java"]
resources = ["src/integration-test/resources"]
```

The conventional roots are `src/integration-test/java` and `src/integration-test/resources`. Custom output belongs under `[build.output] integration`.

## 10.9 Test suites

```toml
[test.suites.smoke]
classes = ["*SmokeTest"]
excludeClasses = ["*FlakySmokeTest"]
tags = ["smoke"]
excludeTags = ["slow"]
workers = 4
locks = [
    { class = "com.example.DatabaseSmokeTest", resources = ["database"] },
]
```

Canonical fields:

- `classes`;
- `excludeClasses`;
- `tags`;
- `excludeTags`;
- `workers`;
- `locks`.

Class patterns match fully qualified Java binary class names, not filesystem paths. `*` matches zero or more class-name characters and `?` matches one character. Path separators are invalid.

`workers` is a positive integer maximum, not a promise that Zolt always launches that many processes. `workers = 1` is the default and is omitted. A value greater than one both enables parallel execution and asserts that the suite is safe to schedule concurrently; actual concurrency is bounded by matching tests and host capacity. There is no separate `parallel` boolean.

`locks` is an array of one-line tables with required exact `class` and nonempty `resources` fields. Each class may appear once. Resource IDs within an entry are unique. Canonical output sorts entries by class name and resources by ID. Resource-lock IDs use the local lowercase kebab-case grammar. Two classes with any resource name in common are never scheduled in the same worker wave. The v1 model has exclusive locks only; values such as `read` and `write` are not lock modes.

## 10.10 Coverage

```toml
[coverage]
line = 88
branch = 74
instruction = 80
method = 85
```

Each field is a finite number in the inclusive `0..100` range and represents a minimum percentage. Integer and fractional TOML numbers are accepted. The table is omitted when no floor is configured.

---

# 11. Toolchains

## 11.1 Zolt pin

```toml
[toolchain.zolt]
version = "0.1.0"
```

The pin is optional and identifies one exact expected Zolt version. A future channel field may be added separately; `version` never changes meaning.

## 11.2 System default

When `[toolchain.java]` is absent, Zolt uses the current system JDK and requires it to support the effective project Java release.

No hidden managed JDK is downloaded merely because `project.java` exists.

## 11.3 Managed/main Java toolchain

```toml
[toolchain.java]
distribution = "graalvm-community"
features = ["native-image"]
policy = "require-managed"
```

Fields:

- `version` — positive integer Java feature release; defaults to effective `project.java`;
- `distribution` — defaults to `temurin` when a managed request exists;
- `features` — default empty;
- `policy` — default `prefer-managed`.

Canonical distributions initially include:

- `temurin`;
- `graalvm-community`.

Canonical policies:

- `prefer-managed` — prefer the locked managed JDK and allow a compatible system fallback;
- `require-managed` — require the locked managed JDK;
- `allow-system` — prefer a compatible system JDK and allow managed use when needed by requested features.

Canonical features initially include `native-image`.

The table cannot be empty. A meaningful field such as `distribution`, `features`, `policy`, or a nondefault `version` must be authored.

`version` is always one concrete feature release integer. `latest`, channels, ranges, vendor build strings, and mutable aliases are rejected. During `toolchain sync` or a resolving operation, Zolt may consult distribution metadata to select the exact current archive for that feature release and platform. The selected vendor release, canonical platform, source URL, archive path, and SHA-256 digest are written to `zolt.lock`.

A locked build never repeats vendor release discovery. An existing valid toolchain lock remains authoritative; `zolt toolchain sync --refresh` is the explicit operation that selects the newest supported GA patch for the authored feature release and rewrites the exact archive evidence. Offline execution requires the exact locked archive to be present and verified. Online repair may replace missing or corrupt toolchain bytes only when the downloaded archive matches the locked digest. User-global toolchain defaults use the same integer feature-release type and the same distribution, feature, and policy tokens as `zolt.toml`.

## 11.4 Test runtime Java toolchain

```toml
[toolchain.java.test]
version = 17
distribution = "temurin"
```

Rules:

- When absent, tests run on the main Java toolchain.
- Supported fields are `version`, `distribution`, and `policy`; the test table must contain at least one.
- `[toolchain.java.test]` may appear without direct fields in `[toolchain.java]`; the TOML-implied parent is not an authored empty main request.
- When a main managed request exists, omitted fields inherit its effective version, distribution, and policy.
- When the main build uses the implicit system JDK, omitted test values default to the effective project Java release, `temurin`, and `prefer-managed`.
- `features` do not inherit and are unsupported in the v1 test-runtime table.
- The test runtime does not alter `project.java` or compilation target.
- The selected test JDK must be able to execute classes compiled for the effective project Java release.

## 11.5 Project and toolchain relationship

- `project.java` is the target Java feature release.
- `[toolchain.java].version` is the JDK feature release used to build.
- `[toolchain.java.test].version` is the JDK feature release used to execute tests when different.
- Managed toolchain patch/build identity, platform, source URL, archive cache path, and digest belong in `zolt.lock`, not the authored manifest.
- The build JDK must support every selected member's project release target.

---

# 12. Packaging, frameworks, native images, and BOMs

## 12.1 Package

```toml
[package]
mode = "uber-jar"
sources = true
javadoc = true
testJar = true
duplicates = "first-wins"
```

Canonical package modes:

- `jar` — default and omitted;
- `uber-jar`;
- `war`;
- `spring-boot`;
- `spring-boot-war`;
- `quarkus`;
- `bom` internally; `mode = "bom"` is rejected in authored source because any BOM domain is the sole canonical signal.

Canonical duplicate policies:

- `fail` — default;
- `first-wins`.

Rules:

- `duplicates` is valid only for `uber-jar`.
- `sources`, `javadoc`, and `testJar` are emitted only when true.
- The selected package mode determines the main publication artifact. There is no redundant `[publish].artifacts` selector.
- `zolt package --mode` and `zolt run-package --mode` are standalone preview overrides only. They are accepted only when substituting the mode leaves the complete resolution fingerprint unchanged.
- Workspace mode overrides are rejected. A mode that changes framework/tool dependencies, package consumability, or other resolution inputs must be authored in `[package]`, followed by a fresh resolve.

## 12.2 Manifest attributes

```toml
[package.manifest]
"Automatic-Module-Name" = "com.example.library"
```

Keys preserve JAR manifest spelling and are quoted when needed.

## 12.3 Spring Boot

```toml
[framework.spring-boot]
native = true
```

The table contains only Spring Boot-specific options. `package.mode = "spring-boot"` or `spring-boot-war` remains the packaging decision.

## 12.4 Quarkus

```toml
[package]
mode = "quarkus"
```

In `0.1.0`, Quarkus packaging has one supported runtime layout: `fast-jar`. The package mode implies that layout, so the authored language does not expose a redundant `[framework.quarkus]` table or one-value `layout` field. When Zolt implements a second real Quarkus layout, it may add a framework option additively while preserving `fast-jar` as the default for existing manifests.

## 12.5 Native image

```toml
[native]
args = ["--no-fallback", "--native-image-info"]
```

Canonical fields:

- `name` — defaults to the effective project name;
- `output` — relative to `[build.output].root`, default `native`;
- `args` — additional native-image arguments.

The table context makes `imageName` unnecessary. The table must contain at least one nondefault field. Native output is owned by the build output root and is removed by `clean`. Native planning consumes the exact configured package output; it never silently substitutes an uber JAR or mutates packaging to make native-image succeed. Unsupported input layouts fail with the package mode and remediation.

Before native-image starts, Zolt computes one ownership plan for the binary, log, framework evidence, private package-input staging directory, configured package artifacts, caches, tool executable, authored inputs, generated roots, and build outputs. Any overlap that could overwrite an input, package artifact, cache entry, tool binary, or another managed output fails before the project is mutated.

Package inputs are copied or linked into a private native staging area only through verified artifact contexts. Native-image writes to a uniquely named same-filesystem staging candidate rather than directly replacing the final binary. After a successful exit, Zolt requires a regular executable candidate and atomically moves it into place. A failed build preserves the previous good binary. Staging cleanup failures are reported without hiding the primary native-image failure.

Native-image execution uses the shared process-tree supervisor. Timeout, interruption, cancellation, or parent termination stops descendants; stdout/stderr and the owned log remain bounded and attributable to the failed invocation.

## 12.6 BOM

```toml
[bom]
members = true

[bom.versions]
"org.postgresql:postgresql" = "42.7.4"

[bom.imports]
"com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }
```

Rules:

- Any authored BOM domain—`[bom]`, `[bom.versions]`, or `[bom.imports]`—implies BOM packaging; canonical output does not repeat `package.mode`.
- An explicitly written `[bom]` table contains `members` and is never empty.
- `members` is `true` for every consumable workspace member or an explicit array of exact member paths.
- Optional `exclude` is an array of exact member paths and is valid only with `members = true`; it removes intentionally unpublished members without introducing a second glob language.
- A standalone or import-only BOM may omit `[bom]` and declare only `[bom.versions]` and/or `[bom.imports]`.
- BOM version and import entries occupy one physical line and participate in `zolt update` and the source-preserving editor.
- A BOM member does not consume `[workspace.project].java` and may not author `project.java`, `project.main`, compilable sources, ordinary dependency lanes, compiler settings, tests, generated producers, native-image settings, or a JAR manifest.

`[bom.versions]` values are a fixed version string or a one-line table containing exactly one of `version` or `versionRef`, plus optional `classifier` and `type`. `[bom.imports]` values are a fixed version string or a one-line table containing exactly one of `version` or `versionRef`; import type and scope are fixed to Maven `pom`/`import`, so classifier and type fields are rejected. Ranges, dynamic versions, interpolation, managed selectors, and workspace selectors are unsupported in both maps.

With `members = true`, the BOM includes every consumable workspace library member except itself and exact paths listed by `exclude`. Every explicit `members = [...]` path must belong to the final workspace member set, must not name the BOM itself, and must produce a consumable library artifact; invalid selections fail rather than being silently omitted. Every `exclude` path must belong to the final member set. A standalone BOM cannot declare `members` or `exclude`.

---

# 13. Generated sources and tools

Generated configuration is advanced by nature, but the common typed generators still avoid tool and output boilerplate.

## 13.1 Built-in tools

Zolt has two reserved built-in tool IDs:

- `openapi`;
- `protobuf`.

Their default coordinates and versions are owned by the installed Zolt release, resolved through the normal artifact resolver, and pinned with exact hashes in `zolt.lock`. Upgrading Zolt may change the default request; the lockfile still makes the selected bytes explicit and reviewable.

Users declare a built-in tool table only to override its request.

OpenAPI override:

```toml
[generated.tools.openapi]
versionRef = "openapi"
```

Optional fields:

- `coordinate`;
- exactly one of `version` or `versionRef`.

Protobuf override:

```toml
[generated.tools.protobuf]
protocVersionRef = "protobuf"
grpcVersionRef = "grpc"
```

Optional coordinate overrides:

- `protocCoordinate`;
- `grpcCoordinate`.

Version selectors:

- `protocVersion` or `protocVersionRef`;
- `grpcVersion` or `grpcVersionRef`.

The reserved built-in IDs derive their kind; they do not repeat `kind = "openapi"` or `kind = "protobuf"`.

## 13.2 Custom named tools

A nonreserved tool requires a kind discriminator.

### Custom typed tool

```toml
[generated.tools.legacy-openapi]
kind = "openapi"
coordinate = "org.openapitools:openapi-generator-cli"
version = "7.11.0"
```

Typed custom kinds are `openapi` and `protobuf` and use the corresponding request fields above.

### Generic JVM tool

```toml
[generated.tools.jooq]
kind = "jvm"
coordinates = [
    { coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" },
    { coordinate = "org.postgresql:postgresql", versionRef = "postgres" },
]
mainClass = "org.jooq.codegen.GenerationTool"
```

Each coordinate declares exactly one of `version` or `versionRef`.

### Process tool

```toml
[generated.tools.node]
kind = "process"
binary = "npm"
versionCommand = ["npm", "--version"]
versionExpect = ">=10 <11"
allowUnpinnedTool = true
```

`allowUnpinnedTool = true` is required for a process tool and is the explicit acknowledgement that Zolt cannot prove PATH binary identity from immutable artifact bytes.

`binary` is a bare executable name resolved by Zolt on the curated PATH; slashes, absolute paths, and shell syntax are rejected in v1. Windows executable suffix lookup is platform-normalized by Zolt. `versionCommand` is a nonempty argv array executed without a shell in the configured tool environment, and its first argument must equal `binary` exactly so Zolt never probes one executable while running another. Zolt captures stdout and stderr separately; the resolved executable path, command, exit status, stripped stdout, and stripped stderr all enter tool identity. `versionExpect` searches stdout first and then stderr for the first dotted-numeric token. It uses a deliberately small comparator grammar: space-separated `>=`, `<=`, `>`, `<`, `=`, `==`, or `!=` terms are ANDed. More elaborate output matching can be added later through a differently named field without changing this contract.

Every probe and generated process runs through the shared supervisor. It uses argv without a shell, bounded diagnostic capture, separate stdout/stderr unless a caller explicitly streams them, and one lifecycle for the complete descendant tree. A timeout, cancellation, interruption, lost parent, or broker failure terminates children and grandchildren before the operation returns.

### Project pseudo-tool

`project` is reserved and never declared:

```toml
tool = "project"
mainClass = "com.example.Codegen"
```

It runs the project's own compiled classes plus resolved runtime classpath.

## 13.3 Presets

```toml
[generated.presets.spring-client]
kind = "openapi"
generator = "java"
library = "webclient"
apiPackage = "com.example.api"
modelPackage = "com.example.model"
configOptions = { useJakartaEe = "true" }
```

A preset carries one `kind` discriminator. Initial OpenAPI preset fields:

- `generator`;
- `library`;
- `apiPackage`;
- `modelPackage`;
- `invokerPackage`;
- `config`;
- `templateDir`;
- `validateSpec`;
- `options`;
- `additionalProperties`;
- `configOptions`;
- `globalProperties`;
- `typeMappings`;
- `importMappings`.

Map-valued fields use inline string maps. Third-party option keys preserve their own spelling.

## 13.4 Common step shape and defaults

Steps live under:

```text
generated.main.<id>
generated.test.<id>
```

Common fields:

- required `kind`;
- optional `tool` where the kind has a reserved default;
- optional or required `output` according to kind;
- `required`, default true;
- `clean`, default true for generated producers and false for `declared-root`;
- `language`, omitted while Java is the sole supported language.

For `openapi` and `protobuf`:

- main `tool` defaults to the matching reserved built-in ID;
- test `tool` defaults the same way;
- main `output` defaults to `<build.output.root>/generated/sources/<stepId>`;
- test `output` defaults to `<build.output.root>/generated/test-sources/<stepId>`.

Canonical output omits these derived values. Custom tool IDs or output paths are authored explicitly.

## 13.5 OpenAPI step

```toml
[generated.main.public-api]
kind = "openapi"
input = "src/main/openapi/public-api.yaml"
preset = "spring-client"
```

Optional `tool` selects a custom OpenAPI tool. Optional `output` overrides the derived path. A step may override any OpenAPI preset field listed above; step values win over preset values.

## 13.6 Protobuf step

```toml
[generated.main.protocol]
kind = "protobuf"
inputs = ["src/main/proto/service.proto"]
javaPackage = "com.example.protocol"
```

Canonical fields:

- optional `tool`, default `protobuf`;
- required `inputs`;
- optional `output`;
- optional `javaPackage`;
- `grpc`, default true and omitted.

Arbitrary protoc plugin configuration remains outside the typed v1 surface. Generic tools use `kind = "exec"` instead.

## 13.7 Exec step

```toml
[generated.main.jooq-model]
kind = "exec"
tool = "jooq"
args = ["src/main/jooq/config.xml"]
inputs = ["src/main/jooq/config.xml", "src/main/resources/db/schema.sql"]
output = "target/generated/sources/jooq"
produces = "java-sources"
```

Required or optional fields:

- required `tool`;
- `mainClass` only for `tool = "project"`;
- optional `args`;
- required nonempty `inputs`;
- required `output`;
- required `produces`;
- `into` for resource-producing lanes;
- `cache`, default `content`;
- `cwd`;
- `env`;
- `secretEnv`;
- `inheritEnv`;
- `timeoutSeconds`;
- `required`;
- `clean`.

Canonical `produces` values:

- `java-sources`;
- `test-sources`;
- `resources`;
- `test-resources`;
- `intermediate`.

Canonical cache values:

- `content` — default and omitted;
- `none`.

Environment maps remain inline:

```toml
cache = "none"
env = { NODE_ENV = "production" }
secretEnv = { DB_PASSWORD = "CODEGEN_DB_PASSWORD" }
inheritEnv = ["HTTP_PROXY"]
```

The generated process receives a curated environment: platform process essentials required by Zolt, literal `env`, mapped `secretEnv`, explicitly named `inheritEnv`, and documented `ZOLT_*` step context. Ambient variables are not inherited accidentally.

For `cache = "content"`, literal environment values enter the fingerprint directly. Each `inheritEnv` name and its current presence/value enter through a cryptographic digest; absence is a distinct fingerprint value. `secretEnv` requires `cache = "none"` in v1. Secret values do not enter fingerprints, and a manually maintained salt would permit stale generated output after a secret-backed input changes. A future cacheable secret-input design must provide a cryptographically and operationally sound invalidation mechanism rather than relying on user memory.

`timeoutSeconds` bounds the whole supervised process tree, not only the direct child. On failure, Zolt preserves the primary start, exit, timeout, cancellation, or output-contract error; descendant cleanup or staging cleanup failures are attached as secondary evidence rather than replacing it.

## 13.8 Declared root

```toml
[generated.test.fixtures]
kind = "declared-root"
inputs = ["src/test/fixtures"]
output = "target/generated/test-sources/fixtures"
required = false
clean = true
```

A declared root registers externally produced source without running a Zolt-owned generator. `inputs` and `output` remain explicit because Zolt does not own the producer.

## 13.9 Scheduling and determinism

Generated-step order is derived from inputs, outputs, and `produces`, not user-declared lifecycle anchors.

Generated input entries are exact project-relative paths or deterministic file globs. Their grammar matches the resource-file grammar: `*` and `?` operate within one segment and complete-segment `**` spans directories. Matching uses `/`, is case-sensitive, does not rely on the host matcher, rejects traversal and symlink escape, and sorts normalized matches before fingerprinting.

With `required = true`, every exact input must exist and every glob entry must match at least one path. With `required = false`, any missing exact input or empty glob makes the whole producer inactive. An inactive producer contributes no generated root or resource lane; when it owns a stale output and `clean = true`, Zolt removes that output so old generated bytes cannot survive after an optional input disappears.

Each producer owns one contained output root. Two producer outputs may not overlap. An output may not overwrite authored source or resources. Inputs that fall under another producer's output create an ordering edge; every other input/output overlap is rejected. `clean = true` clears only the owned output root immediately before execution. A producer that writes outside its declared output fails the build.

- Output-to-input overlap creates an ordering edge.
- Source-producing lanes run before their compiler consumer.
- Resource-producing lanes run before resource copy/package consumption.
- Tie-breaking is deterministic by step ID.
- Cycles are plan errors.
- Tool identity, resolved artifact hashes, arguments, normalized input paths and content, configured environment, output lane, and cache policy enter the producer fingerprint.
- Derived tool/output defaults enter fingerprints through their effective values, even though canonical source omits them.

---

# 14. Publishing

## 14.1 Repository routes

```toml
[publish]
release = "company-releases"
snapshot = "company-snapshots"

[publish.repositories.company-releases]
url = "https://repo.example.com/releases"
credentials = "company"

[publish.repositories.company-snapshots]
url = "https://repo.example.com/snapshots"
credentials = "company"
```

Rules:

- `release` selects the named repository for non-SNAPSHOT versions.
- `snapshot` selects the named repository for SNAPSHOT versions.
- Publication repository IDs use the local lowercase kebab-case grammar.
- Publication credentials reference `[credentials.<id>]`.
- Package mode determines the main artifact; `publish.artifacts` does not exist.
- A multi-file runtime layout such as Quarkus `fast-jar` is not a publishable Maven main artifact; choose a single-archive package mode or publish a separate library module.

## 14.2 Signing

```toml
[publish.signing]
method = "gpg"
keyId = "3AB1C2D3E4F5A6B7"
passphraseEnv = "ZOLT_SIGNING_PASSPHRASE"
```

Fields:

- required `method`; initial value `gpg`;
- optional `keyId`;
- optional `passphraseEnv`.

The required method makes signing explicit and leaves room for a future additive signing backend without changing table identity. The table cannot be empty.

When reproducible signing is requested through `SOURCE_DATE_EPOCH`, `keyId` is required so key selection cannot vary by machine. Secret values are read from the environment and never written to the manifest, lockfile, plan, or log.

## 14.3 Maven Central

```toml
[publish.central]
tokenEnv = "ZOLT_CENTRAL_TOKEN"
mode = "automatic"
```

Optional advanced fields:

```toml
name = "example-library-1.0.0"
url = "https://central.sonatype.com"
```

Canonical modes:

- `manual` — validate and wait for user release;
- `automatic` — publish automatically after validation.

The public Central URL is implicit and omitted. `tokenEnv` names the environment variable containing the Central Portal token.

## 14.4 Publication metadata source

Generated POM metadata comes from `[project]`, `[project.scm]`, and `[project.developers.<id>]`. Packaging contains only artifact-construction settings.

Dependency publication scopes follow the exact lane mapping in §9.2.

---

# 15. Tasks and aliases

Tasks and aliases are first-class manifest concepts.

## 15.1 Tasks

```toml
[tasks.release-notes]
description = "Generate release notes"
run = [
    "zolt",
    "run",
    "--workspace",
    "--member",
    "tools",
    "--",
    "release-notes",
]
cwd = "tools"
env = { RELEASE_CHANNEL = "preview" }
```

Fields:

- optional `description`;
- required nonempty `run` argv array;
- optional root-relative `cwd`;
- optional literal `env` overlay.

Tasks are explicit manual processes, not cached build-graph steps. They inherit the invoking process environment and overlay configured `env` values. They do not use shell strings, interpolation, implicit lifecycle hooks, or environment-assignment prefixes.

Tasks use the same process-tree supervisor as build-owned tools. Standard input is inherited for interactive use; stdout and stderr stream separately. Interrupting Zolt, losing the parent, or cancelling the command terminates the complete descendant tree rather than leaving a daemon or shell child behind. The task's own exit code remains the command exit code, and cleanup failures do not hide the primary process result.

Arguments after `zolt task <id> --` append to the configured argv without shell interpretation.

A root task resolves `cwd` from the workspace root. A member task resolves it from the member root. Every resolved cwd remains contained beneath its owning root.

## 15.2 Aliases

```toml
[aliases]
ci = ["check", "--context", "ci", "--all"]
deps = ["outdated"]
```

Aliases expand only to built-in Zolt commands. External programs belong in tasks. Additional user arguments append to the expanded built-in command.

Root and member task/alias IDs share one effective namespace. Collisions fail rather than shadow.

## 15.3 Local IDs

Workspace names and task, alias, suite, tool, preset, step, developer, repository, credential, publication-repository, resource-token, resource-lock, and version-alias IDs use lowercase kebab-case:

```text
[a-z][a-z0-9]*(?:-[a-z0-9]+)*
```

Examples: `zolt`, `release-notes`, `spring-client`, `company-releases`, `integration-smoke`, `openapi`.

Rules:

- letters are lowercase ASCII;
- digits are allowed after the first character;
- hyphens separate words;
- no leading, trailing, or repeated hyphen is allowed;
- uppercase letters, underscores, dots, whitespace, and quoted escape names are rejected.

Project names, Maven artifact IDs, paths, environment-variable names, Java classes, and external map keys remain domain values and are not subject to this grammar. Project names are still recommended to use lowercase kebab-case because they become Maven artifact IDs.

Reserved IDs include built-in command names for aliases/tasks, `all` for test suites, `project` for generated tools, `openapi` and `protobuf` for built-in generated tools, and `central`/`order` in repository control.

---

# 16. Complete canonical examples

## 16.1 Minimal application

```toml
[project]
name = "hello"
version = "0.1.0"
group = "com.example"
java = 21
main = "com.example.Main"

[dependencies.test]
"org.junit.jupiter:junit-jupiter" = "5.13.4"
```

## 16.2 Library with an API boundary

```toml
[project]
name = "http-client"
version = "1.0.0"
group = "com.example"
java = 21

[dependencies.api]
"org.slf4j:slf4j-api" = "2.0.17"

[dependencies]
"com.fasterxml.jackson.core:jackson-databind" = "2.19.0"

[dependencies.test]
"org.junit.jupiter:junit-jupiter" = "5.13.4"
```

The SLF4J API lane is visible to compile-time consumers. Jackson is implementation-private and is published as runtime scope.

## 16.3 Zolt workspace root

```toml
[workspace]
name = "zolt"

[workspace.members]
default = ["apps/zolt"]
include = ["apps/*", "modules/*"]

[workspace.project]
group = "sh.zolt"
version = "0.1.0-SNAPSHOT"
java = 21
license = "Apache-2.0"

[toolchain.java]
distribution = "graalvm-community"
features = ["native-image"]
policy = "require-managed"

[coverage]
line = 88
branch = 74
```

## 16.4 Spring Boot service

```toml
[project]
name = "orders-api"
version = "0.1.0"
group = "com.example.orders"
java = 21
main = "com.example.orders.Application"

[versions]
spring-boot = "4.0.6"

[platforms]
"org.springframework.boot:spring-boot-dependencies" = { versionRef = "spring-boot" }

[dependencies]
"org.springframework.boot:spring-boot-starter-webmvc" = { managed = true }

[dependencies.runtime]
"org.postgresql:postgresql" = { managed = true }

[dependencies.dev]
"org.springframework.boot:spring-boot-devtools" = { managed = true }

[dependencies.test]
"org.springframework.boot:spring-boot-starter-test" = { managed = true }

[package]
mode = "spring-boot"
```

## 16.5 Workspace member

With the Zolt root defaults above, a member begins compactly:

```toml
[project]
name = "zolt-toml"

[dependencies]
"sh.zolt:zolt-model" = { workspace = true }

[dependencies.test]
"org.junit.platform:junit-platform-console-standalone" = "1.11.4"
```

The member inherits group, version, Java release, license, shared aliases/platforms, repositories, and toolchain policy according to the closed workspace rules.

## 16.6 Enterprise repository with Central fallback

```toml
[repositories.company]
url = "https://repo.example.com/maven"
credentials = "company"

[credentials.company]
usernameEnv = "MAVEN_USERNAME"
passwordEnv = "MAVEN_PASSWORD"
```

## 16.7 Central-ready library

```toml
[project]
name = "example-library"
version = "1.0.0"
group = "com.example"
java = 21
description = "A reusable Java library."
url = "https://example.com/library"
issues = "https://github.com/example/library/issues"
license = "Apache-2.0"

[project.scm]
url = "https://github.com/example/library"
connection = "scm:git:https://github.com/example/library.git"
developerConnection = "scm:git:ssh://git@github.com/example/library.git"
tag = "v1.0.0"

[project.developers.maintainer]
name = "Example Maintainer"
email = "maintainer@example.com"

[package]
sources = true
javadoc = true

[publish.signing]
method = "gpg"
keyId = "3AB1C2D3E4F5A6B7"
passphraseEnv = "ZOLT_SIGNING_PASSPHRASE"

[publish.central]
tokenEnv = "ZOLT_CENTRAL_TOKEN"
mode = "automatic"
```

The file is longer because the project genuinely owns publication, signing, and Central metadata. Ordinary builds remain small.

## 16.8 Workspace BOM member

With workspace project defaults supplying group, version, and license, a family BOM remains compact and carries no Java target:

```toml
[project]
name = "platform-bom"

[bom]
members = true
exclude = ["apps/admin"]

[bom.versions]
"org.postgresql:postgresql" = "42.7.4"

[bom.imports]
"com.fasterxml.jackson:jackson-bom" = "2.19.0"
```

The BOM itself and nonconsumable application members are never added to its managed member set.

---

# 17. Canonical namespace reference

| Canonical path | Purpose |
| --- | --- |
| `[workspace]` | Stable workspace identity |
| `[workspace.members]` | Workspace inclusion, exclusion, and defaults |
| `[workspace.project]` | Narrow shared project defaults |
| `[project]` | Project identity, Java release target, basic publication metadata |
| `[project.scm]` | Source-control metadata |
| `[project.developers.<id>]` | Named developer metadata |
| `[toolchain.zolt]` | Optional Zolt pin |
| `[toolchain.java]` | Main Java toolchain request |
| `[toolchain.java.test]` | Test runtime Java toolchain |
| `[versions]` | Fixed version aliases |
| `[repositories]` | Dependency repository control and explicit order |
| `[repositories.<id>]` | Named dependency repository |
| `[credentials.<id>]` | Shared environment-based repository credentials |
| `[platforms]` | Imported dependency platforms/BOMs |
| `[dependencies]` | Implementation dependencies |
| `[dependencies.api]` | Exported API dependencies |
| `[dependencies.runtime]` | Runtime-only dependencies |
| `[dependencies.provided]` | Compile-provided dependencies |
| `[dependencies.dev]` | Development-run-only dependencies |
| `[dependencies.test]` | Test dependencies |
| `[dependencies.processor]` | Main annotation processors |
| `[dependencies.test-processor]` | Test annotation processors |
| `[dependencies.constraints]` | Strict dependency constraints |
| `[dependencies.policy]` | Conflict and direct-dependency policy |
| `[dependencies.policy.licenses]` | Global license policy |
| `[dependencies.license-exceptions.<coordinate>]` | Reviewed exact-coordinate license exceptions |
| `[build]` | Nonstandard main source roots |
| `[build.output]` | Nonstandard output layout |
| `[build.metadata]` | Reproducible build metadata |
| `[compiler]` | Main compiler behavior |
| `[compiler.test]` | Test compiler overrides |
| `[compiler.generated]` | Annotation-processor generated roots |
| `[resources]` | Nonstandard resource roots |
| `[resources.filter]` | Resource filtering |
| `[resources.tokens]` | Resource token sources |
| `[generated.tools.<id>]` | Named generator/exec tools |
| `[generated.presets.<id>]` | Named generator presets |
| `[generated.main.<id>]` | Main generated-source steps |
| `[generated.test.<id>]` | Test generated-source steps |
| `[test.sources]` | Nonstandard test source roots |
| `[test.runtime]` | Test process settings |
| `[test.integration]` | Integration-test roots |
| `[test.suites.<id>]` | Named test suites |
| `[coverage]` | Coverage floors |
| `[package]` | Package mode and supplemental artifacts |
| `[package.manifest]` | JAR manifest attributes |
| `[bom]` | BOM behavior |
| `[bom.versions]` | BOM-managed versions |
| `[bom.imports]` | Imported BOMs in a published BOM |
| `[framework.spring-boot]` | Spring Boot-specific options |
| `[native]` | Native-image output |
| `[publish]` | Release and snapshot repository routes |
| `[publish.repositories.<id>]` | Publication repositories |
| `[publish.signing]` | Artifact signing |
| `[publish.central]` | Maven Central Portal |
| `[tasks.<id>]` | Explicit external/manual tasks |
| `[aliases]` | Built-in Zolt command aliases |

## 17.1 Reserved structure

The parser rejects unknown top-level sections. The schema above is the authored surface for `0.1.0`.

Dynamic collections reserve structural children and built-in IDs. In particular:

- `central` and `order` are reserved by repository control;
- `project`, `openapi`, and `protobuf` are reserved generated-tool IDs;
- `all` is reserved as the aggregate test suite;
- built-in command names cannot be task or alias IDs.

## 17.2 Canonical Zolt symbols

The schema registry owns every symbolic value and validates lowercase kebab-case for compound tokens. Initial families include:

- package modes: `jar`, `uber-jar`, `war`, `spring-boot`, `spring-boot-war`, `quarkus`;
- toolchain distributions: `temurin`, `graalvm-community`;
- toolchain policies: `prefer-managed`, `require-managed`, `allow-system`;
- toolchain features: `native-image`;
- conflict policy: `resolve`, `warn`, `fail`;
- generated tool kinds: `openapi`, `protobuf`, `jvm`, `process`;
- generated step kinds: `openapi`, `protobuf`, `exec`, `declared-root`;
- generated lanes: `java-sources`, `test-sources`, `resources`, `test-resources`, `intermediate`;
- generated cache policy: `content`, `none`;
- signing method: `gpg`;
- Central mode: `manual`, `automatic`;
- compiler JDK API mode: `release`, `host`;
- test runtime event outcomes: `passed`, `skipped`, `failed`.

External strings such as Maven `type = "test-jar"`, SPDX expressions, URLs, and command arguments are not Zolt symbols and retain their domain spelling.

---

# 18. Parser, writer, and schema architecture

## 18.1 One schema registry

Manifest names must not remain scattered through independent `Set.of(...)` declarations.

Introduce one internal schema registry:

```java
record ManifestField(
        ManifestPath path,
        ManifestValueKind valueKind,
        FormattingPolicy formatting,
        MutationPolicy mutation,
        int canonicalOrder) {}
```

A section registry owns:

```java
record ManifestSection(
        ManifestPath path,
        SectionKind kind,
        int canonicalOrder,
        Set<String> reservedChildren) {}
```

The registry drives:

- accepted canonical paths;
- field types;
- unknown-field diagnostics;
- field and section order;
- lowercase kebab-case table segments, local IDs, and symbolic values;
- lower camel case field names;
- one-line mutation requirements;
- canonical output;
- JSON Schema/editor metadata;
- documentation tables;
- source-editor routing;
- exact-update surface discovery and opaque target-ID inputs.

There are no legacy aliases in the final registry.

## 18.2 Parser layers

The parser has three explicit stages:

1. **TOML parse:** syntax, positions, and raw tables.
2. **Manifest-shape validation:** allowed sections, exact source shapes for mutable maps, casing/symbol validation, path normalization, and one-line requirements.
3. **Semantic construction:** project, workspace, dependency, build, and feature models.

Unknown fields fail with the nearest canonical suggestion.

## 18.3 Document model

A parsed authored manifest retains exact source:

```java
record ZoltManifestDocument(
        String source,
        ManifestSyntax syntax,
        AuthoredManifest authored) {}

record EffectiveManifest(
        AuthoredManifest authored,
        Optional<WorkspaceContext> workspace,
        EffectiveProject project) {}
```

`AuthoredManifest` represents every accepted source domain without materializing inherited values back into the member. `EffectiveManifest` applies workspace project defaults, shared maps, repository policy, toolchains, and coverage with source provenance.

Canonical member writing emits authored values only; it never writes inherited workspace defaults into every member file. Resolution, planning, quality checks, IDE export, and publication consume the effective model.

Mutation commands retain the source document and workspace context until commit time.

## 18.4 Canonical writer

The canonical writer:

- emits only the final language;
- omits defaults;
- uses schema-defined ordering;
- uses lowercase kebab-case for table segments, local IDs, and symbolic values, and lower camel case for field names;
- uses `/` for manifest paths on every platform;
- sorts unordered dynamic maps;
- emits repository precedence only through the explicit `order` field;
- never emits empty inline tables;
- emits all source-mutated dynamic entries on one physical line;
- never emits a source shape the ordinary editor cannot safely mutate.

## 18.5 Source-preserving editor

The editor supports these mutable tables:

```text
versions
platforms
dependencies
dependencies.api
dependencies.runtime
dependencies.provided
dependencies.dev
dependencies.test
dependencies.processor
dependencies.test-processor
dependencies.constraints
bom.versions
bom.imports
```

Requirements:

- one explicit table header;
- one assignment line per dynamic entry;
- complete value-span replacement on that line;
- exact line deletion for removal;
- safe table creation;
- source reparse after patch;
- semantic equality with the requested manifest;
- no unrelated source changes;
- compare-and-set commit against exact captured bytes.

Comment ownership is conservative:

- updating a value preserves key spelling, spacing, and the same-line trailing comment;
- removing an entry removes its assignment line and same-line trailing comment but never guesses that preceding standalone comments belong to it;
- moving an entry between dependency lanes carries its same-line trailing comment to the new assignment; preceding standalone comments remain in place;
- an existing table that becomes empty is retained so comments and deliberate placement are not destroyed.

When a target table is missing, the editor inserts it at the schema-defined boundary for its domain when that boundary is unambiguous; otherwise it appends one canonically separated table at end of file. It never reorders existing tables to make the file look canonical.

The editor fails closed when a source position or safe span is unavailable.

## 18.6 No general formatter in the critical path

A comment-preserving formatter is valuable but not required to finalize the language.

For `0.1.0`:

- `init` emits canonical source;
- checked-in fixtures and examples are canonical;
- public mutations preserve source;
- `zolt config validate` validates the final language;
- `zolt config show --effective` explains defaults.

A future `zolt config format` must preserve comments and use complete source spans. It is an additive tool, not a language prerequisite.

---

# 19. Failure-safe mutation contract

The recent manifest safety architecture remains mandatory and is completed at the workspace boundary.

## 19.1 Mutation scope

Every mutation resolves its authoritative scope before reading the manifest or deciding that the operation is a no-op:

```java
record ManifestMutationScope(
        Path manifestPath,
        Path authoritativeLockfile,
        Path journalDirectory,
        Optional<WorkspaceMemberRef> member) {}
```

Standalone project:

```text
manifestPath          = project/zolt.toml
authoritativeLockfile = project/zolt.lock
journalDirectory      = project/.zolt/manifest-edits/project
```

Workspace member:

```text
manifestPath          = workspace/<member>/zolt.toml
authoritativeLockfile = workspace/zolt.lock
journalDirectory      = workspace/.zolt/manifest-edits/<member-id>
```

## 19.2 Workspace member mutation

A member mutation:

1. Acquires the workspace mutation lock.
2. Recovers pending journals before any no-op check.
3. Parses the exact member manifest and complete workspace snapshot.
4. Applies the member source patch in memory.
5. Resolves the whole workspace using an in-memory override for that member.
6. Stages the member manifest and authoritative root lockfile.
7. Compare-and-set commits both against captured bytes.
8. Never creates `<member>/zolt.lock`.

## 19.3 Recovery states

Recovery distinguishes live file state, not just journal labels:

| Live manifest | Live lock | Decision |
| --- | --- | --- |
| original | original | Rollback/cleanup is complete |
| staged | original | Restore original manifest |
| staged | staged | Complete commit and clean journal |
| original | staged | Restore original lock or refuse if unsafe |
| anything else | anything else | Refuse to overwrite concurrent/manual changes |

Recovery is discoverable from the workspace root and does not depend on rerunning the exact command from the exact member directory.

## 19.4 Lockfile compare-and-set

The final expected-content comparison and lockfile replacement occur while holding the same cross-process lock.

Required primitive:

```java
AtomicLockfileWriter.compareAndSetAtomically(
        lockfilePath,
        expectedSnapshot,
        replacement);
```

A concurrent ordinary `zolt resolve` cannot land between verification and replacement.

## 19.5 Transaction durability boundary

The minimum release guarantee is safe recovery from normal exceptions and process termination on a functioning local filesystem.

The implementation should force staged data and journal state where supported. The design does not claim transactional durability across arbitrary storage corruption, broken network filesystems, or hardware failure without evidence.

## 19.6 Commands covered

The contract applies to:

- `add`;
- `remove`;
- platform edits;
- version alias edits;
- BOM version/import edits;
- `update`;
- future manifest-mutating commands.

Recovery still runs for no-op, dry-run, and “already absent” entry paths when a pending journal exists.


---

# 20. CLI alignment

The CLI mirrors manifest nouns without reproducing TOML hierarchy mechanically.

Canonical forms:

```console
zolt add org.junit.jupiter:junit-jupiter:5.13.4 --scope test
zolt remove org.junit.jupiter:junit-jupiter --scope test

zolt versions set junit 5.13.4
zolt versions remove junit

zolt platforms set io.netty:netty-bom 4.1.119.Final
zolt platforms set org.springframework.boot:spring-boot-dependencies --version-ref spring-boot
zolt platforms remove io.netty:netty-bom

zolt bom versions set org.postgresql:postgresql 42.7.4
zolt bom versions set org.postgresql:postgresql --version-ref postgresql
zolt bom versions remove org.postgresql:postgresql
zolt bom imports set com.fasterxml.jackson:jackson-bom --version-ref jackson
zolt bom imports remove com.fasterxml.jackson:jackson-bom

zolt toolchain global status
zolt toolchain global use java 21 --temurin

zolt workspace members
zolt task release-notes
```

Decisions:

- `zolt version` exclusively reports the installed Zolt version.
- `zolt versions` owns aliases in `[versions]`.
- `zolt platforms` owns entries in `[platforms]`; there is no singular `zolt platform` alias.
- `zolt bom versions` and `zolt bom imports` own the two BOM maps; their nesting is semantic disambiguation, not a mechanical reproduction of every TOML table.
- Map mutations use `set` as an idempotent upsert and `remove` as an idempotent exact-key removal. The coordinate argument is always `group:artifact`; a fixed version is a separate positional value and is mutually exclusive with `--version-ref`.
- `zolt bom versions set` alone accepts `--classifier` and `--type`. BOM imports and platforms reject those flags because their artifact semantics are fixed.
- These mutations refresh the authoritative lock by default. Their common `--no-resolve` option commits only the source-safe manifest edit, reports that the lock is stale, and names the exact resolve command; recovery still runs before a no-op or `--no-resolve` decision.
- Dependency scope is an explicit `--scope` option, not a positional prefix.
- CLI scope values are `implementation` (default), `api`, `runtime`, `provided`, `dev`, `test`, `processor`, and `test-processor`.
- Global toolchain actions use one nested command grammar rather than duplicate `--global` forms.
- CLI flags and commands remain kebab case.
- Manifest task IDs are passed exactly as authored. Positional IDs are manifest data, not CLI grammar, so a command such as `zolt task release-notes` does not weaken the kebab-case rule for Zolt command and option names.

## 20.1 Exact update automation

Policy-driven human updates remain simple. Automation that must select one declaration exactly uses the versioned machine contract:

```console
zolt outdated --format json --schema-version 2
zolt update --target-id zt1_... --to 2.1.0 --format json --schema-version 2
```

Schema-v2 outdated output groups targets by authoritative scope and reports canonical mutation-root-relative `manifestPath` and `lockfilePath`, one opaque `zt1_` target ID, updateability, blocker, current version, candidates, source, and alias fan-out where applicable.

A target ID is derived from canonical manifest path, surface kind, canonical section, and declaration identifier. It is stable across current-version, candidate, fan-out, and lockfile-content changes, but callers must treat it as opaque and rediscover it from the selected Zolt root. The hard cut does not retain pre-cut IDs because canonical source paths and sections are changing before public RC; after the cut, the ID version prefix owns future identity evolution.

Exact update mode:

- requires `--target-id` and `--to` together;
- accepts one fixed, supported, strictly newer destination version;
- performs no repository metadata discovery;
- revalidates the target and every contextual blocker under the mutation lock;
- stages the authored manifest and authoritative lock together unless `--no-resolve` is explicitly selected;
- uses staged resolution to prove artifact availability and rolls back both files on failure;
- for a workspace, revalidates every captured member manifest, root policy manifest, and root lock before compare-and-set commit;
- returns structured `changed`, `applied`, `resolved`, `changedFiles`, target identity, and alias fan-out evidence.

The final target catalog covers version aliases, dependencies, platforms, constraints, BOM pins/imports, and generated-tool coordinates. Root-owned named values have one source location, so mirrored root/member target blockers are unnecessary in the final language.

Policy-driven `zolt update --format json` may keep its existing schema-v1 contract. Exact-target schema v2 remains a separate stable write/read pair rather than being mixed into policy update output.

## 20.2 Configuration inspection


```console
zolt config validate
zolt config show --effective
zolt config show --manifest
```

`show --manifest` reports the parsed authored values without silently expanding or rewriting the file. `show --effective` includes defaults and workspace-shared values with source provenance.

`zolt config show` requires exactly one of `--manifest` and `--effective`; the flags are mutually exclusive and the bare command fails with usage rather than choosing a hidden default. The selected view is rooted at the manifest discovered from the command directory: `--manifest` never materializes workspace inheritance, while `--effective` resolves the complete workspace context and identifies every value as authored, inherited, or built-in. Neither mode reads or reports machine-local user-global configuration.

The pre-cut `zolt config show` user-global diagnostic is removed in this hard cut. It is not moved to `config global`, retained as a bare-command default, or preserved as an alias. Machine-local configuration remains an implementation input where otherwise specified, but this final manifest-oriented command surface does not expose a second meaning for `config show`.

---

# 21. Implementation strategy — one hard cut

There is no public migration lifecycle and no compatibility promise for the current pre-release syntax.

## 21.1 Final-tree rule

The final implementation merged to main must:

- parse only the final language;
- emit only the final language;
- contain no old key aliases;
- contain no old token aliases;
- contain no public migration command;
- have every checked-in manifest, example, fixture, smoke project, document, and test converted;
- write only lockfile version 7 with member-qualified authored dependency roots separate from resolved scopes, while retaining the current content-addressed artifact contract and exact-update machine identity rules.

## 21.2 Self-host cutover

Zolt's self-hosting creates a compiler-bootstrap sequence, not a reason to support two manifest languages.

Cutover sequence:

1. Pin a known-good pre-cut Zolt binary or commit as the bootstrap builder.
2. Implement the final parser, writer, models, and command changes while the checked-in manifests still use the pre-cut language. The bootstrap binary compiles this source without executing the new parser. Any internal module-edge changes required by the implementation must remain expressible to that bootstrap builder; otherwise land those edges before the parser cut.
3. Before changing manifests, use the bootstrap binary to produce and retain a transition binary whose parser understands only the final language.
4. Convert the root and every member manifest in one atomic branch change.
5. Use the retained transition binary to resolve the final manifests and regenerate the authoritative lockfile.
6. Use that final manifest and lockfile state to perform two clean self-host builds; the second build must be byte-for-byte equivalent wherever Zolt claims reproducibility.

No source revision needs a parser that accepts both languages. The transition artifact is a build bootstrap, not a compatibility dialect, alias, or migration path.

## 21.3 Implementation phases

### Phase 0 — Freeze schema and golden fixtures

- Land this document in `docs/manifest-language-design.md`.
- Create golden manifests for every example in §16.
- Freeze table paths, field names, symbolic values, field order, and sparse defaults.
- Freeze the `unicode-17.0.0-nfc-full-default-case-fold` data identity and checksums.
- Freeze workspace-members JSON schema v1, the final initializer/config/map-mutation CLI forms, and the lock-v7 dependency-root wire shape.
- Add a source grep gate for removed pre-release spellings.

### Phase 1 — Complete authored and effective models

- Introduce complete `AuthoredManifest` and `EffectiveManifest` aggregates.
- Add `[workspace]`, `[workspace.project]`, and source provenance.
- Represent integer Java feature releases as validated value objects.
- Preserve dependency lane as a first-class semantic value.
- Keep internal domain views immutable.

### Phase 2 — Strict parser and canonical writer

- Centralize the schema registry.
- Parse only final paths and symbols.
- Enforce local-ID casing and the three-segment budget.
- Emit sparse canonical output and inherited member omission.
- Reject old forms with ordinary unknown-field/value diagnostics, not compatibility hints.

### Phase 3 — Workspace membership and shared root semantics

- Implement strict candidate expansion and pre-validation exclusion.
- Add deterministic normalization, portability collision checks, and evidence.
- Implement the checked-in Unicode 17.0.0 NFC/full-default-fold portability key without host Unicode tables.
- Implement exact defaults and `[workspace.project]` inheritance.
- Make root dependency repositories authoritative.
- Give every root-owned version, platform, and credential ID one source location; reject member redeclaration even when equal.
- Add `zolt workspace members` text and JSON.
- Make JSON schema v1 byte-deterministic and version-selected.
- Fingerprint the effective member graph, not raw patterns.

### Phase 4 — Dependency language, lanes, and publication

- Move scopes under `[dependencies.*]`.
- Add explicit managed/workspace selectors.
- Preserve API versus implementation through resolver requests, lockfile records, workspace projection, classpaths, IDE models, SBOMs, tree/why output, and POM generation.
- Write lockfile version 7 `dependencyRoot` records with authored `DependencyLane` and independent resolved scope; never infer a lane from a pre-v7 lock.
- Map API to Maven compile and implementation to Maven runtime.
- Replace per-edge exclusion objects with exact coordinate strings and name project-wide direct-dependency policy `deny`.
- Remove speculative constraint `kind` while strict is the sole semantic.
- Update CLI scope values and all lockfile readers/writers.
- Make BOM versions/imports source-safe update surfaces.
- Preserve schema-v2 exact update discovery/writes over the final canonical paths and surface registry.
- Add SPDX expression evaluation and `[dependencies.license-exceptions.<coordinate>]` with stale-exception auditing.

### Phase 5 — Source-safe editing and transactions

- Extend one-line representation guards to versions, platforms, every dependency lane, and constraints.
- Update editable canonical table paths.
- Preserve comments and unchanged bytes.
- Implement only the final plural `platforms` and nested `bom versions`/`bom imports` mutation commands, with common transaction and `--no-resolve` behavior.
- Retain current mutation-scope, recovery-before-no-op, workspace-root-lock, and compare-and-set guarantees.
- Add in-memory whole-workspace resolution for member edits.
- Preserve opaque target revalidation, all-manifest workspace snapshots, and canonical changed-file evidence for exact updates.

### Phase 6 — Remaining language domains

- Repository control/order and shared credentials.
- Build/output/compiler structure.
- Resource filters and tokens.
- Test runtime, integration roots, suites, workers, and class resource locks.
- Numeric toolchain versions, dynamic exact-release selection, and locked archive integrity.
- Package/framework/native/BOM fields, package-mode override boundaries, native output preflight, and atomic binary publication.
- Shared subprocess supervision for tasks, generated tools, Java execution, toolchain commands, and native-image.
- Generated tools, presets, and steps.
- Project publication metadata.
- Signing method and Central modes.
- Tasks and aliases.

Each subsystem lands with parser, writer, semantic, diagnostic, JSON/editor-schema, and documentation tests in the same change.

### Phase 7 — Repository conversion and cleanup

- Convert every checked-in `zolt.toml`.
- Replace repeated member identity fields with `[workspace.project]` where inherited.
- Regenerate `zolt.lock` as a content-addressed executable lock and regenerate the native-image reflection inventory.
- Update examples, smoke fixtures, docs, CLI help, and benchmark generators.
- Delete old codecs, old value tokens, and scattered key registries; verify no dual-language parser path exists.

### Phase 8 — Release gates

- Run all unit and CLI tests.
- Run workspace resolve/build/test/package/publish planning.
- Run native-image and smoke matrices.
- Run content-addressed lock corruption/repair, exact-update schema-v2, process-tree cancellation, and atomic native publication canaries.
- Perform two consecutive clean self-host builds.
- Verify every generated manifest equals its golden canonical source.
- Verify no removed manifest spelling remains outside explicitly historical prose.

---

# 22. Test contract

## 22.1 Schema and naming

```text
allCanonicalTableSegmentsUseLowerKebabCase
allCanonicalFieldNamesUseLowerCamelCase
allCanonicalSymbolValuesUseLowerKebabCase
allLocalIdsUseLowerKebabCase
workspaceNameUsesLocalIdGrammar
projectArtifactNameKeepsMavenGrammar
unknownFieldSuggestsCanonicalName
unknownTopLevelSectionFails
removedPreReleaseFieldNamesFail
removedPreReleaseModeTokensFail
canonicalWriterEmitsNoRemovedNames
schemaRegistryAndDocumentationStayInSync
unicodePortabilityDataVersionAndChecksumsAreFrozen
```

External values such as Maven coordinates and SPDX expressions are excluded from casing tests.

## 22.2 Sparse output and workspace project defaults

```text
initOmitsMavenCentral
initOmitsConventionalBuildPaths
initOmitsDefaultJarMode
initOmitsFalseValues
workspaceWriterRequiresStableName
workspaceWriterUsesMembersTable
workspaceInitEmitsExplicitDefaultUnlessAllWasRequested
workspaceInitAllMembersOmitsDefault
workspaceInitAllMembersRequiresWorkspaceMode
workspaceMemberCanInheritGroupVersionJavaAndLicense
workspaceMemberNameNeverInherits
memberWriterDoesNotMaterializeInheritedValues
memberOverrideWinsOverWorkspaceProjectDefault
standaloneProjectRequiresCompleteIdentity
zoltRootFitsGoldenManifest
minimalApplicationFitsGoldenManifest
```

## 22.3 Workspace discovery

```text
exactMemberIsIncluded
singleSegmentGlobIncludesOnlyImmediateValidProjects
multiSegmentPatternMatchesExactDepth
globIgnoresDirectoriesWithoutManifest
excludedInvalidManifestIsIgnored
nonExcludedInvalidManifestFails
exactIncludeExcludedIsRejected
excludeFiltersCandidatesBeforeManifestValidation
globDoesNotMatchDotDirectories
exactHiddenDirectoryCanBeIncluded
globDoesNotFollowSymlinks
unsupportedRecursiveGlobIsRejected
partialSegmentGlobIsRejected
backslashPathIsRejected
parentTraversalIsRejected
includeEntryMustContributeFinalMember
staleExcludeIsReportedByCheck
duplicateArrayEntryIsRejected
overlappingPatternsAreAllowed
duplicateMatchesRetainPatternEvidence
sameRealDirectoryViaTwoPathsIsRejected
unicodeNormalizationCollisionIsRejected
portableCaseCollisionIsRejected
portableCaseFoldUsesUnicode17FullDefaultMappings
portableCaseFoldRejectsNonScalarPathText
duplicateEffectiveProjectIdentityIsRejected
finalMembersAreSortedDeterministically
defaultRejectsPattern
defaultMustBeFinalMember
explicitDefaultDoesNotGrowWithNewMember
omittedDefaultUsesVisibleImplicitAllSelection
rootDotMemberIsSupported
newValidDirectoryChangesEffectiveMemberSet
newNonProjectDirectoryDoesNotChangeMembership
changingOnlyDefaultDoesNotStaleLock
changingPatternsWithSameEffectiveSetDoesNotStaleLock
lockedResolveReportsAddedMember
workspaceMembersJsonDefaultsToSchemaV1
workspaceMembersJsonSchemaV1MatchesGolden
workspaceMembersJsonUsesOnlyRelativeSortedPaths
workspaceMembersJsonRejectsUnsupportedSchemaBeforeOutput
```

Run expansion tests on Linux, macOS, and Windows.

## 22.4 Workspace sharing

```text
rootProjectDefaultsAreInheritedWithProvenance
rootVersionsAreVisibleToMembers
rootRepositoryUniverseIsAuthoritative
memberDependencyRepositorySectionIsRejected
rootCredentialsAreVisibleToMembers
rootOwnedNamedValueCannotBeRedeclaredEvenWhenEqual
conflictingRootAndMemberNamedValueFails
memberMainToolchainReplacesRootMainRequestAsAWhole
memberTestToolchainCanInheritRootMainRequest
rootCoverageIsMinimum
memberCoverageCanRaiseButNotLowerFloor
rootAndMemberTaskIdCollisionFails
projectOnlySectionFailsInVirtualWorkspaceRoot
```

## 22.5 Repository semantics

```text
absentRepositoriesMeansCentralOnly
customRepositoryWithoutCentralFieldKeepsCentral
centralFalseDisablesCentral
centralTrueIsAcceptedButCanonicallyOmitted
centralStringUsesReplacementUrl
centralInlineTableSupportsAuthenticatedMirror
centralReplacementCredentialsUseDeclaredAuthenticationScheme
centralSubtableIsRejected
emptyRepositoriesTableIsRejected
emptyExternalUniverseWorksWithoutExternalRequests
emptyExternalUniverseFailsOnFirstExternalRequest
remoteRepositoryHttpUrlIsRejected
loopbackRepositoryHttpUrlIsAllowed
repositoryUrlCannotContainUserInfoOrFragment
defaultRepositoryOrderSortsCustomIdsAndPlacesCentralLast
explicitOrderMustListEveryEnabledRepositoryExactlyOnce
tableDeclarationOrderDoesNotChangeLookupOrder
reservedRepositoryIdsCannotBeCustom
workspaceMembersShareOneRepositoryUniverse
selectedRepositorySourceIsLocked
lockfileUsesContentAddressedCacheRelativePaths
artifactPathDigestEqualsRecordedSha256
malformedOrAbsoluteLockedArtifactPathFails
repositoryCacheScopeExcludesSecretValues
unauthenticatedMetadataCacheIsRepositoryScoped
authenticatedMetadataIsNotPersistedForOfflineDiscovery
localOverlayIsSnapshottedBeforeLocking
onlineRepairRequiresExactLockedDigest
offlineCorruptArtifactFailsClosed
allExecutableConsumersShareArtifactVerification
```

## 22.6 Dependency semantics and source safety

```text
apiLaneReachesDownstreamCompileAndPublishesCompile
implementationLaneStaysOffDownstreamCompileAndPublishesRuntime
runtimeLaneIsNotOnMainCompile
providedLaneIsOnTestsButNotRuntimeOrPackage
devLaneIsOnlyOnDevelopmentRun
processorLanesStayOffRuntimeClasspath
lockfilePreservesAuthoredDependencyLane
lockfileVersionSevenSeparatesLaneFromResolvedScope
lockfileV7DependencyRootIsMemberQualified
apiAndImplementationRootsMayShareResolvedCompileScope
publishOnlyRootOmitsResolvedScope
preV7LockIsNeverMappedToAuthoredLane
canonicalDependencyEntryIsOneLine
canonicalPlatformEntryIsOneLine
canonicalConstraintEntryIsOneLine
canonicalBomVersionEntryIsOneLine
canonicalBomImportEntryIsOneLine
writerNeverWrapsMutableEntry
multilineInlineDependencyIsRejected
longFormDependencyTableIsRejected
dottedDependencyAssignmentIsRejected
multilineVersionAliasIsRejected
emptyManagedTableIsRejected
managedTrueRoundTrips
workspaceTrueUsesEffectiveInheritedIdentity
workspaceTrueResolvesUniqueMember
workspaceTrueRejectsMissingMember
workspaceTrueRejectsAmbiguousIdentity
coordinateKeyRejectsVersionWhitespaceAndUnsafeCharacters
classifierAndTypeRemainExplicitVariantFields
sameVariantCannotRepeatAcrossOrdinaryLanes
differentClassifierIsASeparateVariant
processorVariantMayAlsoBeAnOrdinaryDependency
constraintFixedStringShorthandRoundTrips
constraintHasNoKindWhileStrictIsSoleSemantic
optionalEdgeDoesNotPropagateTransitively
optionalEdgeRemainsAvailableToDeclaringProject
optionalMetadataIsRejectedWherePropagationIsMeaningless
publishOnlyRequiresFixedOrAliasVersion
publishOnlyIsRejectedInTestDevAndProcessorLanes
publishOnlyNeverEntersRuntimeGraphs
policyDenyBlocksDirectDependency
managedPublishedDependencyUsesExactLockedVersion
platformsAreNotImplicitlyReExportedFromLibraryPom
updateEditsBomVersionsAndImportsSourceSafely
licenseStringRequiresSingleSpdxIdentifier
compoundLicenseUsesInlineMetadata
licenseDenyOverridesAllow
spdxAndRequiresEveryBranch
spdxOrSelectsOneAcceptableBranchDeterministically
spdxWithIsAnIndivisiblePolicyTerm
malformedSpdxEvidenceFailsClosedAsUnmapped
separateMavenLicenseRecordsRemainAlternatives
licenseExceptionRequiresGlobalAllowlist
licenseExceptionCannotOverrideDeny
licenseExceptionMatchesExactCoordinateAndOptionalVersion
licenseExceptionRequiresReasonAndCanonicalTerms
licenseExceptionMissingVersionMismatchAndRedundantFailAudit
dependencyUpdateNeverRubberStampsLicenseExceptionReview
licenseExceptionVersionIsNotAnUpdateTarget
workspaceLicenseExceptionIsMemberLocal
licenseAllowlistAndDualLicenseSemanticsAreStable
addPreservesCommentsAndUnrelatedSections
removeDeletesOnlyOnePhysicalAssignment
updateChangesOnlyValueSpan
crlfIsPreserved
```

## 22.7 Mutation transactions

```text
memberMutationUpdatesRootLockAndCreatesNoMemberLock
noOpMutationRecoversPendingJournal
dryRunEntryRecoversPendingJournal
fullyStagedFilesCompleteInsteadOfRollback
failedResolveRestoresManifestAndLock
concurrentManifestEditIsRejected
transactionCannotOverwriteConcurrentResolve
scopeMovePreservesSameLineComment
precedingCommentIsNeverGuessedAsEntryOwned
emptyTableIsRetainedAfterRemoval
missingTableUsesCanonicalDomainBoundary
recoveryRefusesUnknownLiveBytes
journalIsDiscoverableFromWorkspaceRoot
outdatedSchemaV2UsesCanonicalRelativePaths
updateTargetIdIsOpaqueCanonicalAndStable
exactUpdatePerformsNoMetadataDiscovery
exactUpdateRevalidatesWorkspaceSnapshotAndRootLock
exactUpdateRollsBackManifestAndLockOnResolveFailure
exactUpdateReportsCanonicalChangedFiles
rootOwnedTargetHasOneSourceLocation
platformAndBomNoResolveLeavesAuthoritativeLockStale
```

## 22.8 Compiler, tests, and toolchains

```text
projectJavaIsPositiveInteger
projectJavaIsSoleReleaseTarget
standaloneBomDoesNotRequireJava
bomRejectsAuthoredJavaAndCompilableDomains
compilerReleaseFieldIsRejected
compilerGeneratedPathsAreOutputRootRelative
systemJdkIsUsedWhenToolchainTableAbsent
managedToolchainVersionDefaultsToProjectJava
managedToolchainRejectsLatestChannelsAndRanges
managedToolchainResolutionLocksExactVendorArchive
lockedToolchainArchiveIsVerifiedBeforeUse
offlineToolchainRequiresLockedCachedBytes
userGlobalToolchainUsesSameFinalValueGrammar
graalvmCommunityDistributionRoundTrips
emptyToolchainTableIsRejected
testToolchainCanExistWithoutMainManagedRequest
testToolchainPolicyInheritanceIsExplicit
testJdkMustRunProjectRelease
suitePatternsMatchClassNamesNotPaths
workersGreaterThanOneEnablesParallelScheduling
suiteLockEntriesRoundTripAndSortByClass
classLocksPreventSharedResourceWave
testLockResourcesAreUniqueAndCanonicallySorted
testLockValuesAreResourceNamesNotModes
workersIsAPositiveMaximum
resourceFilterRequiresNonemptyInclude
resourceGlobSemanticsArePlatformIndependent
selectedBinaryResourceFails
resourceEnvironmentTokenDigestIsNotClaimedAsASecretBoundary
duplicateRelativeResourcePathFailsWithoutImplicitPrecedence
nativeDefaultsToProjectNameAndBuildOutputRoot
nativeUsesConfiguredPackageArtifactWithoutModeSubstitution
nativeOutputPlanRejectsEveryInputOutputCacheAndToolOverlap
nativePackageInputUsesPrivateVerifiedStaging
nativeCandidateMustBeRegularAndExecutable
nativeBinaryPublicationIsAtomicAndPreservesPreviousBinary
nativeCleanupFailureDoesNotHidePrimaryFailure
projectRelativePathRejectsAbsoluteParentAndControlSegments
realPathContainmentPreventsSymlinkEscape
environmentNamesUsePortableGrammar
environmentCaseCollisionIsRejected
testRuntimeEventsAcceptOnlyPassedSkippedAndFailed
testRuntimeEventDuplicatesAreRejected
testRuntimeEventsUseFixedCanonicalOrder
testRuntimeValuesRejectProjectRootInterpolation
```

## 22.9 Generated sources

```text
allToolKindsShareGeneratedToolsCollection
builtInToolIdsAreReserved
builtInOpenApiToolAndOutputDefaultsAreOmitted
builtInProtobufToolAndOutputDefaultsAreOmitted
customTypedToolRequiresKind
openapiPresetRoundTrips
protobufToolRoundTrips
execToolRoundTrips
projectPseudoToolIsNotDeclared
producerOrderIsDerivedFromIo
generatedInputGlobIsPlatformIndependent
optionalProducerCannotLeaveStaleOutputAfterInputDisappears
producerOutputOverlapIsRejected
producerCannotWriteOutsideOwnedOutput
processBinaryRequiresBareCuratedPathName
processVersionCommandMustProbeTheConfiguredBinary
processProbeCapturesStdoutAndStderrDeterministically
processVersionExpectUsesDocumentedNumericGrammar
processTimeoutTerminatesDescendantTree
processCancellationTerminatesDescendantTree
processOutputCaptureIsBoundedAndStreamsRemainSeparate
processCleanupFailureDoesNotHidePrimaryFailure
inheritedEnvironmentValueChangesContentFingerprint
secretEnvRequiresNoCache
generatedDefaultsAreOmitted
```

## 22.10 Publication and commands

```text
projectMetadataProducesCentralReadyPom
apiAndImplementationMapToDifferentMavenScopes
packageModeDeterminesMainPublishArtifact
standalonePackageModeOverrideRequiresStableResolutionFingerprint
workspacePackageModeOverrideIsRejected
resolutionChangingModeRequiresManifestEditAndResolve
quarkusModeUsesFastJarWithoutFrameworkTable
frameworkQuarkusTableIsRejectedInV1
workspaceBomCanExcludeExactMembers
bomExplicitMemberMustBeConsumableAndNotSelf
bomExcludePathMustBeFinalWorkspaceMember
bomValueGrammarRejectsManagedWorkspaceAndDynamicSelectors
publishArtifactsFieldIsRejected
signingMethodIsRequired
reproducibleSigningRequiresKeyId
centralManualAndAutomaticModesRoundTrip
rootAndMemberTasksUseDifferentCwdBases
taskArgumentsAppendWithoutShell
taskInheritsStdinAndStreamsStdoutAndStderrSeparately
taskInterruptionTerminatesDescendantTree
taskCleanupFailureDoesNotHidePrimaryExit
aliasesTargetBuiltInCommandsOnly
configShowRequiresExactlyOneManifestView
configShowDoesNotExposeUserGlobalConfig
platformsUsesPluralSetRemoveGrammar
singularPlatformCommandIsRemoved
bomVersionsAndImportsUseNestedSetRemoveGrammar
bomVersionsAloneAcceptsClassifierAndType
```

## 22.11 Full repository gates

- Unit tests.
- CLI surface tests.
- Stable JSON contract tests, including workspace-members schema v1 and outdated/update schema v2 exact targets.
- Content-addressed lock and cache integrity canaries.
- Process-tree supervision and cancellation canaries.
- Native-image reflection inventory and atomic output-publication canaries.
- Clean self-host build one.
- Clean self-host build two.
- Workspace resolve/build/test/package/publish planning.
- Smoke suite.
- Checked-in manifest golden check.
- Search gate rejecting removed manifest names, camel-case table names, camel-case local IDs, and camelCase symbolic values.

---

# 23. Acceptance criteria

The language is ready for the first public RC only when every statement below is true:

1. Every workspace has an explicit stable `name`; workspace-name values are identifiers and are preserved as authored.
2. Workspace membership uses only `[workspace.members]` with strict exact paths and complete-segment `*` patterns.
3. Exclusions filter candidates before manifest validation, and exact include/exclude contradictions fail.
4. An authored `default` accepts exact final member paths only; omitting it visibly selects dynamic `implicit-all`.
5. A newly matched project changes lock freshness, never changes an authored default selection, and joins default selection only when the workspace deliberately uses `implicit-all`.
6. `[workspace.project]` removes repeated group, version, Java, and license boilerplate without becoming general inheritance.
7. Canonical member output never materializes inherited values.
8. Zolt's root manifest fits on one screen and conventional members can begin with only project name.
9. Every fixed Zolt table segment, local ID, and compound Zolt symbolic value follows lowercase kebab-case; every fixed field name follows lower camel case.
10. Canonical manifests contain no camelCase enum, mode, policy, capability, kind, or lane token.
11. A new standalone application has no repository or conventional-build boilerplate.
12. Maven Central remains the default when custom repositories are added and changes only through explicit `central` control.
13. Repository precedence is explicit data or deterministic ID order, never table source order.
14. Repository URLs and credential handling satisfy the authored HTTPS safety contract.
15. One workspace uses one authoritative dependency repository universe.
16. Compilable projects and Java toolchains use positive integer feature releases with distinct target-versus-JDK semantics; BOMs carry no project Java target.
17. Dependency scopes all live under `[dependencies.*]` and remain distinct through every downstream model.
18. API dependencies reach consumer compile classpaths and Maven compile scope; implementation dependencies do not.
19. Optional dependencies are accepted only in propagating API, implementation, and runtime lanes; they remain available to the declaring project but do not propagate transitively.
20. Managed and workspace dependencies are explicit.
21. Published dependencies carry exact locked versions, and ordinary library POMs do not silently re-export platforms.
22. Every mutable dependency, platform, version, constraint, BOM-version, and BOM-import declaration is one physical line.
23. Multiline, dotted, empty, and long-form dependency declarations fail actionably.
24. Strict constraints have no speculative `kind` field.
25. Project publication identity no longer lives under package metadata.
26. Credentials are shared through `[credentials.<id>]` without secret values in source.
27. Signing declares a required method and cannot use an empty table.
28. Default values, inherited values, and false flags are omitted.
29. No canonical table path exceeds three segments.
30. No empty table enables a feature or selects behavior; retained empty collection tables mean only an empty collection.
31. Resource collisions, filtering globs, test suite patterns, workers, and resource locks have exact platform-independent semantics.
32. Cacheable generated steps cannot depend on opaque secret values in v1.
33. Every workspace command uses one authoritative root lock; member commands never create or consume member lockfiles.
34. Recovery runs before no-op and dry-run decisions.
35. Final lock replacement is compare-and-set safe against concurrent resolve.
36. Ordinary mutations preserve all unrelated source bytes and conservatively preserve entry comments during lane moves.
37. The self-host cutover is executable without any parser that accepts both languages.
38. The final parser contains no compatibility aliases.
39. The final repository contains no public migration command, dual-language parser, or checked-in bootstrap bridge.
40. All checked-in manifests, docs, examples, smoke fixtures, generators, and help use the final vocabulary.
41. Root-owned version aliases, platforms, and credentials have one source location and cannot be redeclared by members.
42. Global license policy evaluates pinned SPDX terms and expressions deterministically, and exact reviewed exceptions cannot override deny or remain stale.
43. Executable lockfiles use verified content-addressed cache-relative paths and preserve repository/source provenance without secrets.
44. Missing or corrupt locked artifacts repair only to the exact digest online and fail closed offline.
45. Schema-v2 outdated/update automation uses opaque exact target IDs, canonical relative paths, staged resolution, and workspace-wide compare-and-set revalidation.
46. Managed Java feature releases resolve to exact locked vendor archives; locked execution does not rediscover mutable releases.
47. Every Zolt-owned subprocess has bounded output and descendant-tree cancellation semantics that preserve the primary failure.
48. Native output ownership is preflighted and a verified executable candidate atomically replaces the prior binary only after success.
49. Package-mode CLI overrides cannot hide a resolution-changing or workspace configuration change.
50. Two consecutive clean self-host builds and the complete release matrix pass.
51. Workspace portability comparison uses checked-in Unicode 17.0.0 NFC and full-default-case-fold data with verified checksums, never host Unicode behavior.
52. Workspace-members JSON schema v1 is closed, version-selected, workspace-relative, deterministically ordered, and covered by a byte-for-byte golden.
53. Workspace initialization writes exact defaults unless `--all-members` is explicitly supplied with `--workspace`.
54. `config show` requires exactly one manifest view, and platform/BOM map mutations expose only the final plural/nested `set`/`remove` grammar.
55. Every final lock is version 7 and carries member-qualified authored dependency roots independently from resolved scopes; no pre-v7 scope is inferred as an authored lane.

---

# 24. Explicitly deferred

These are additive future capabilities, not gaps that require changing the final syntax:

- multiline dependency values after complete multiline source-span editing exists;
- long-form dependency subtables after the same editor work;
- recursive workspace patterns;
- partial-segment workspace wildcards;
- symlinked members;
- nested workspaces;
- configuration includes/fragments;
- member-specific dependency repository universes;
- public plugin-defined manifest namespaces;
- a comment-preserving general formatter;
- additional toolchain distributions, package modes, generator kinds, and policies;
- additional Quarkus layouts and a `[framework.quarkus]` table once more than one real authored choice exists;
- richer typed resource-filter policies;
- wildcard, group-wide, classifier-aware, or version-range license exceptions;
- cacheable generated steps whose outputs depend on opaque secret values;
- more expressive process-version probes beyond the documented numeric comparator grammar.

A deferred feature must preserve the language laws: sparse common path, stable lexical categories, bounded structure, deterministic behavior, and failure-safe editing.

---

# 25. Final signature

The signature workspace is:

```toml
[workspace]
name = "zolt"

[workspace.members]
default = ["apps/zolt"]
include = ["apps/*", "modules/*"]

[workspace.project]
group = "sh.zolt"
version = "0.1.0-SNAPSHOT"
java = 21
license = "Apache-2.0"
```

The signature workspace member is:

```toml
[project]
name = "zolt-model"
```

The signature standalone project is:

```toml
[project]
name = "hello"
version = "0.1.0"
group = "com.example"
java = 21
main = "com.example.Main"

[dependencies.test]
"org.junit.jupiter:junit-jupiter" = "5.13.4"
```

The language rule is:

> **Kebab-case for namespaces, IDs, and symbolic values; camelCase for fields; explicit identity; structure before repetition; no table without a job; no default without a reason; no syntax Zolt cannot edit safely.**
