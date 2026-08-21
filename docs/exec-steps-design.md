# Exec Steps — Design Record

Status: decided 2026-07-22, synthesized from a three-lens design panel
(determinism-first, enterprise-coverage-first, minimalist-first). This is the
implementation contract for the generic exec build-step surface.

## Thesis

Zolt already runs a third-party tool reproducibly: the OpenAPI generated-source
path resolves a tool by Maven coordinate into a locked non-app scope, runs
`java -cp <locked jars> <mainClass> <args>` over declared inputs into a declared
output directory, and content-fingerprint-skips the run. The only things that
make it OpenAPI-specific are a hardcoded main class and a hardcoded flag
mapping. Exec steps are that machinery with the tool identity lifted into
configuration — not a plugin system, not a task graph, not a lifecycle.

One sentence a step can say: **run this pinned tool on these declared inputs to
produce this owned output, consumed by the build.** Anything not expressible
that way is out of scope by definition and belongs in `[tasks.<id>]` or CI.

## The surface

Steps live on the existing generated-producer lane, not a new subsystem:

```toml
[versions]
jooq = "3.19.15"
postgres = "42.7.4"

# Named tool. kind discriminates acquisition: "jvm" = resolver-locked
# coordinates (SHA-256 in zolt.lock, tool-exec scope, never on app classpaths);
# "process" = PATH binary (see the PATH gate below).
[generated.tools.jooq]
kind = "jvm"
coordinates = [
  { coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" },
  { coordinate = "org.postgresql:postgresql", versionRef = "postgres" },
]
mainClass = "org.jooq.codegen.GenerationTool"

# A step: declared inputs -> one owned output, joined to a consumption lane.
[generated.main.jooq-model]
kind = "exec"
tool = "jooq"
args = ["src/main/jooq/config.xml"]
inputs = ["src/main/jooq/config.xml", "src/main/resources/db/schema.sql"]
output = "target/generated/sources/jooq"
produces = "java-sources"          # or "resources", with into = "static"
cache = "content"                  # default
```

```toml
# PATH tool (frontend). The triple gate: probe + explicit ack + advisory lock.
[generated.tools.node]
kind = "process"
binary = "npm"
versionCommand = ["npm", "--version"]   # probed stdout enters the fingerprint
versionExpect = ">=10 <11"              # optional fail-fast guard
allowUnpinnedTool = true                # explicit: PATH bytes are unprovable

[generated.main.frontend-install]
kind = "exec"
tool = "node"
cwd = "web"
args = ["ci"]
inputs = ["web/package.json", "web/package-lock.json"]
output = "web/node_modules"
produces = "intermediate"          # consumed only by other steps, never packaged

[generated.main.frontend-build]
kind = "exec"
tool = "node"
cwd = "web"
args = ["run", "build"]
inputs = ["web/package-lock.json", "web/node_modules", "web/src/**", "web/vite.config.ts"]
output = "web/dist"
produces = "resources"
into = "static"
env = { NODE_ENV = "production" }
```

`tool = "project"` is a built-in pseudo-tool: the member's own compiled classes
plus resolved runtime classpath (Maven `exec:java` parity). Declaring
`target/classes/**` as an input schedules it after compile; such a step may only
produce resources or test-lane outputs — producing main sources from main
classes is a cycle and a plan blocker.

## Scheduling: derived, never declared

The panel's sharpest disagreement was lifecycle anchors (one design wanted a
7-anchor enum, one a 5-anchor enum, one zero). Verdict: **no user-facing anchor
concept.** A step's position is derived entirely from its declared IO:

- `produces = "java-sources" | "test-sources"` → runs before that compile.
- `produces = "resources" | "test-resources"` → runs before resource copy /
  packaging (`into` maps the subtree).
- `produces = "intermediate"` → runs only because another step consumes its
  output.
- An input matching another step's declared output creates an ordering edge;
  an input under `target/classes` schedules the step after compile.

Ordering is the topological sort of those edges, tie-broken alphabetically by
step id; cycles are a parse/plan error. v1 executes steps serially. There is no
`anchor =`, no `after =`, no `dependsOn` — a step exists because its output is
consumed, not because a phase fires. `zolt plan` shows every step's derived
position, inputs, outputs, tool identity, and cache policy, so the plan remains
the whole truth.

## Determinism contract

- Fingerprint (producer skip-gate, modeled on the OpenAPI cache): tool identity
  (`kind = "jvm"`: locked jar hashes; `kind = "process"`: binary name + probed version), argv,
  content hashes of declared inputs (globs expanded by Zolt, sorted),
  env NAMES and configured literal values, cwd, produces/into. Skip iff
  fingerprint matches and the output exists.
- Consumer fence: actual output bytes are hashed into the module build
  fingerprint (a new execOutputs section beside generatedSources, not limited
  to `*.java`). This lands in the same change as step execution — an always-run
  step with stable bytes still lets downstream skip; a changed byte invalidates
  exactly its consumers.
- `cache = "content"` (default) | `"none"`. `"none"` is the honest
  nondeterminism escape (live-DB jOOQ): always runs, is excluded from
  `--offline` (hard error), fails `zolt check --require-offline-ready`, and
  stamps `hermetic = false` into package evidence. Zolt never fakes a cache key
  for an oracle it cannot hash — no probe-the-database cleverness in v1 (a
  probe that under-approximates the oracle serves stale codegen silently, the
  exact sin this design exists to prevent; sanctioned paths are committed
  DDL or `cache = "none"`).
- Environment is curated, never inherited: OS essentials (PATH, HOME) + the
  step's literal `env` + `secretEnv` indirection
  (`TARGET_NAME = "SOURCE_ENV_NAME"`, values never in config, lock,
  fingerprints, plans, or logs) + `ZOLT_*` step context. `inheritEnv = [...]`
  is the explicit passthrough list.
- argv arrays only. No shell strings, no `sh -c`, no interpolation; Zolt owns
  glob expansion. cwd and every declared path are real-path-contained under the
  project root (the existing TaskCommand sandbox rule).
- After a cacheable step runs, files under its cwd whose mtime advanced but
  that fall outside declared outputs are an undeclared-output error
  (check failure naming the path).
- Per-step `timeoutSeconds` (default 600); timeout kills the process with an
  actionable error.

## What stays refused

- In-process plugins / SPI / user classes in the build JVM (native-image
  closed world; also the plugin-swamp firewall).
- Lifecycle hooks and phase attachment of any kind, including for
  `[tasks.<id>]` — tasks stay manual-invoke, uncached, outside the graph.
- Shell strings, ambient environment inheritance, build-time network fetch as
  an input mechanism (tools arrive only via the resolver or PATH; a step that
  reaches the network owns `cache = "none"`).
- In-place source mutation (formatters route to `zolt task` + a dirty-tree
  check), multi-output globs, root-level cross-member steps (v1), parallel step
  execution (v1), new typed codegen kinds — `GeneratedSourceKind` grows by
  exactly `EXEC` and then freezes; openapi/protobuf remain as shipped ceilings.

## Staged implementation

1. **jvm runner, end to end (M):** model types (`ExecGenerationSettings`,
   `ProducesLane`, `DependencyScope.TOOL_EXEC` + toolGroup), TOML codec,
   resolver tool contributor, `ExecGeneratedSourceService` + generalized
   producer cache, IO-derived scheduling, execOutputs consumer fence, plan
   node + blockers, `zolt check` validation, sources + resources lanes.
   Covers jOOQ-from-DDL, Avro, ANTLR, xjc, GraphQL, in-house coordinate tools.
2. **process runner + honesty modes (M):** PATH triple gate + advisory lock
   entry, `tool = "project"`, secretEnv, timeouts, `cache = "none"` +
   hermetic=false evidence + offline-ready gate, undeclared-output scan,
   intermediate lane. Covers frontend and live-DB reality.
3. **migration + docs (S–M):** explain signals (exec-mappable WARN with drafted
   TOML + stated fidelity; nondeterministic and unmappable BLOCKs), USAGE
   "Generated Producer Contract" section, jooq + frontend canary examples.
   Optional later: `cache = "output-verify"` CI tripwire.

## Panel positions preserved

The determinism design contributed the two-layer cache split, the consumer
execOutputs fence, closed env, and the undeclared-output scan; its
`inputs.probe` oracle-hashing was deferred as a silent-staleness risk, and its
anchor enum was replaced by IO-derived scheduling. The coverage design
contributed the demand table (~35% frontend, ~22% exec:java), the jvm/process
runner split, `tool = "project"`, secretEnv, timeouts, and hermetic=false
evidence; its 5-anchor enum and intra-anchor `after` DAG were subsumed by
derived scheduling. The minimalist design won the surface location (the
generated lane, no new subsystem), the GeneratedSourceKind freeze, the refusal
of anchors and of task-lifecycle attachment, and the framing that the refusal
list is the product; its rejection of step chains was relaxed exactly once —
ordering edges derived from declared IO — because the npm install→build pair is
too common to refuse and derivation adds no new vocabulary.
