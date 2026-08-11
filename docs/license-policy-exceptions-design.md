# Scoped License Exceptions and SPDX Expressions — Design Record

Status: decided and implemented 2026-08-11. Amendment to
[`docs/supply-chain-design.md`](supply-chain-design.md) for
`[dependencyPolicy.licenses]`, `zolt licenses`, CycloneDX output, and
`zolt check --check license-policy`.

## Decision

Add scoped dependency exceptions and SPDX-expression evaluation as one change.
Exceptions without expression semantics would still mis-evaluate the motivating
`MIT AND BSD-3-Clause` declaration, while expression semantics without scoped
exceptions would force a project to allow `BSD-3-Clause` globally.

The configuration surface is:

```toml
[dependencyPolicy.licenses]
allow = ["MIT", "Apache-2.0", "Unicode-3.0"]
deny = ["GPL-3.0-only"]
unknown = "fail"

[dependencyPolicy.licenses.exceptions."org.example:matchit"]
allow = ["BSD-3-Clause"]
version = "0.8.4"
reason = "Reviewed transitive dependency; declared as MIT AND BSD-3-Clause"
```

The exception extends the allow-list only while evaluating the exact
`org.example:matchit` package. `version` is optional; when present it is an
exact match, not a Maven range. `reason` and a non-empty `allow` list are
required.

With that policy, `org.example:matchit:0.8.4` declaring
`MIT AND BSD-3-Clause` is permitted, but an unrelated dependency declaring
`BSD-3-Clause` is still a violation.

## Non-negotiable semantics

1. Global `deny` wins before any global or scoped allowance. An exception can
   never override a denied license.
2. An exception matches one exact `group:artifact` key. There are no wildcards,
   group-wide rules, classifiers, artifact types, or version ranges.
3. An exception may allow canonical SPDX license terms only. It cannot allow
   `UNKNOWN`, an unmapped raw string, or an entire `AND`/`OR` expression.
4. `reason` is required and is emitted anywhere the exception affects a
   verdict. The feature is called an exception, never an ignore or suppression.
5. An exception must be exercised by at least one compile/runtime dependency in
   the policy owner's closure. A missing coordinate, mismatched version, or
   redundant exception is a policy failure.
6. Existing enforcement scope does not widen: compile/runtime dependencies are
   enforced; test, provided, dev, and tool dependencies remain report-only.
7. Workspace policy remains member-local. A member's exception only applies to
   that member's dependency closure.
8. License resolution remains cache-only and deterministic. No lockfile schema
   or network behavior changes.

## Why a keyed table

The dependency coordinate is identity, not data, just as it is for
`[dependencyConstraints]`. A quoted table key makes duplicate exceptions
structurally impossible and gives diagnostics a stable configuration path:

```text
[dependencyPolicy.licenses.exceptions."org.example:matchit"]
```

An array of inline tables was rejected because it permits duplicate coordinates
and makes source-preserving edits and diagnostics position-dependent. A version
inside the table is retained because one resolved workspace cannot need two
simultaneous approvals for the same `group:artifact`; if that constraint ever
changes, the surface should be redesigned rather than quietly adding ranges.

## Model and TOML placement

`modules/zolt-model` gains:

```text
LicensePolicyException
  dependency: group:artifact
  allow: sorted canonical SPDX terms
  version: optional exact string
  reason: non-blank string

LicensePolicySettings
  allow
  deny
  unknown
  exceptions: sorted map keyed by group:artifact
```

`LicensePolicySettings.isDefault()` returns false when an exception exists.
The existing three-argument constructor stays as a compatibility overload that
uses an empty exception map.

`DependencyPolicySectionCodec` accepts `exceptions` under the licenses table,
validates every nested table against exactly `allow`, `version`, and `reason`,
and writes coordinates in lexical order. Parse-time failures include:

- malformed coordinates or wildcard characters;
- empty `allow` or blank `reason`;
- any exception when the global `allow` list is empty (there is no restrictive
  baseline for it to extend);
- blank versions or Maven range syntax;
- non-canonical or unknown SPDX terms in an exception;
- an exception term also covered by global `deny`;
- unknown fields at any level.

Global `allow` and `deny` retain their current raw-string compatibility for
unmapped Maven metadata. SPDX identifiers and `WITH` combinations are
canonicalized as policy terms. A valid compound `AND`/`OR` expression is not a
policy term and is rejected with a migration diagnostic that names its terms;
the same applies to malformed expression-shaped input. In particular, a current
workaround such as `allow = ["MIT AND BSD-3-Clause"]` must become term-level
policy plus a scoped exception. This deliberate config break prevents the old
raw-string escape from bypassing the new AST semantics. The stricter SPDX-only
rule for exception entries ensures the feature cannot turn unidentified legal
text into a narrow approval.

## SPDX expression model

`modules/zolt-model`, in the single-owner package `sh.zolt.license`, gains a
small, dependency-free parser and immutable AST:

```text
SpdxExpression
  LicenseId(id)
  With(licenseId, exceptionId)
  And(left, right)
  Or(left, right)
```

The parser implements parentheses and the SPDX precedence order `WITH`, `AND`,
then `OR`. Operators accept the SPDX uppercase or lowercase forms and render in
canonical uppercase form. License and exception identifiers are canonicalized
against a source-pinned SPDX catalog; parsing never consults the network.

The first implementation supports SPDX License List identifiers and exception
identifiers. `LicenseRef-*`, `AdditionRef-*`, `DocumentRef-*`, and the legacy
`+` suffix remain unmapped in this stage because Zolt has no accompanying
license-document or extracted-text model. They may be added later without
changing the AST evaluation rules.

The repository pins SPDX License List 3.28.0 in sorted resource files under
`zolt-model` and exposes that version in tests. The resources carry their
upstream version/source header and have matching GraalVM resource metadata so
the JVM and native CLI read identical catalogs. Catalog updates are explicit
source changes, not mutable runtime data. Keeping the parser beside the policy
model lets `zolt-toml` validate configuration and `zolt-sbom` parse evidence
through their existing dependency on `zolt-model`; putting it in `zolt-sbom`
would force the TOML layer into a dependency cycle. Deprecated curated aliases such as
`GPL-2.0-with-classpath-exception` normalize to the canonical expression
`GPL-2.0-only WITH Classpath-exception-2.0`.

## Maven evidence boundary

Maven recommends an SPDX identifier in `<license><name>`, but its POM model does
not assign `AND` or `OR` semantics to multiple `<license>` elements. Zolt must
not invent legal meaning that the publisher did not provide.

`PomLicenseResolver` therefore applies these rules in order:

1. If a raw license name is a valid explicit SPDX expression, preserve its AST
   and canonical expression text.
2. Otherwise apply the existing conservative name/URL mapping to a single SPDX
   term.
3. Otherwise retain the raw record as `UNMAPPED`.
4. No readable declaration remains `UNKNOWN` as today.
5. Multiple Maven `<license>` records remain alternatives, preserving Zolt's
   current behavior. They are not rewritten as an asserted publisher-supplied
   expression.

An expression-shaped string that fails parsing stays `UNMAPPED` with its raw
text and the existing unmapped diagnostic; it never falls through to a partial
or best-effort AST. That keeps `zolt licenses` reportable while allowing
`unknown = "fail"` to enforce strictness.

The existing `LicenseIndex` coordinate map remains stable; its `SbomLicense`
values evolve into the declaration model capable of preserving either an
explicit expression, discrete Maven alternatives, `UNMAPPED`, or `UNKNOWN`.
The resolver still memoizes by `group:artifact:version` for one command
invocation only.

## Evaluation algebra

Evaluation computes a complete per-dependency decision before filtering. The
reportable decision contains the canonical declaration, final verdict, decisive
term, reason, and an optional matched exception record. Globally permitted
decisions remain implicit in the public result; warnings, violations, and
permitted-by-exception decisions are retained because they carry evidence or
require action.

Reporting annotations are keyed by coordinate plus rendered declaration label,
not coordinate alone. A component with several Maven license records must not
show a decisive `BSD-3-Clause` exception or violation on its unrelated `MIT`
row. Summary and enforcement remain per dependency; only row attribution uses
the finer key.

An SPDX term is evaluated in this order:

1. If the full term is globally denied, it is a violation.
2. For `A WITH E`, a global deny of base license `A` also makes the combined
   term a violation.
3. If global `allow` is empty, the term is permitted globally.
4. If the exact term is in global `allow`, it is permitted globally.
5. If a matching dependency exception allows the exact term, it is permitted
   by exception.
6. Otherwise it is a violation for not appearing in the authoritative allow
   list.

`WITH` is one indivisible policy term. Allowing `GPL-2.0-only` does not
implicitly approve every exception to it, and allowing one `WITH` combination
does not approve the base license by itself. Denying the base license still
denies every combination, which preserves the global-deny boundary.

The AST combines term decisions as follows:

```text
A AND B  -> both branches must be acceptable; strictest branch wins
A OR B   -> one acceptable branch is sufficient; best branch wins
A WITH E -> evaluate the exact combined term
```

For deterministic `OR` selection, prefer a globally permitted branch, then a
branch permitted by exception, then `WARN`, then `VIOLATION`; ties use canonical
expression order. This avoids claiming an exception was used when an allowed
global option already satisfies the declaration.

For discrete Maven records, retain the existing alternative rule and use the
same deterministic selection order. This is an explicit compatibility boundary,
not a claim that Maven encoded `OR`.

`UNMAPPED` and `UNKNOWN` retain the current `unknown = fail|warn|allow` matrix.
An `OR` branch that is globally permitted may satisfy the expression even when
another branch is unknown; an `AND` expression retains that unknown branch's
warning or violation because both obligations apply.

## Exception lifecycle

After evaluating one policy owner, the evaluator audits every configured
exception:

- **used:** the coordinate and optional version matched, and at least one term
  required the exception to reach a permitted verdict;
- **version-mismatched:** the coordinate exists at another version; emit one
  failure naming reviewed and resolved versions;
- **missing:** the coordinate is absent from the enforced closure; emit one
  stale-exception failure;
- **redundant:** the coordinate matched, but the declaration passed globally or
  did not contain an allowed exception term; emit one failure asking for policy
  cleanup.

Failures are deliberate. Dead exceptions are review debt, and silently carrying
one would make later dependency changes harder to audit. Version mismatch is
reported once rather than as both a license violation and a stale exception.

In workspace aggregation, each member is audited against its own policy and
closure before results merge. Audit records retain the owning member path, so
identical exceptions in two members remain distinct and actionable. The existing
strictest-member rule extends to:

```text
VIOLATION > WARN > PERMITTED_BY_EXCEPTION > PERMITTED_GLOBALLY
```

An aggregate pass annotated `permitted-by-exception` therefore means at least
one consuming member relies on an exception, even if another member permits the
same dependency globally.

## Reporting contracts

### Text

`zolt licenses` groups an explicit SPDX expression under its canonical text and
shows an exception on the dependency, never as ignored:

```text
MIT AND BSD-3-Clause (1)
  org.example:matchit:0.8.4  [exception] BSD-3-Clause permitted by
    [dependencyPolicy.licenses.exceptions."org.example:matchit"]
    reason: Reviewed transitive dependency; declared as MIT AND BSD-3-Clause
```

Violations identify the failing branch of the expression. `zolt check` uses the
same decision object and produces a next step appropriate to the cause: a global
deny never suggests an exception; an allow-list miss may suggest either a global
allowance or an exact reviewed exception.

Optional-scope entries remain unannotated because the enforcing command still
does not evaluate them.

### Zolt-native JSON

The current schema remains version 1 and changes additively. Expression groups
retain `license` and `status`, and add `expression`. A component that relies on
an exception gains:

```json
{
  "policy": {
    "status": "permitted-by-exception",
    "expression": "MIT AND BSD-3-Clause",
    "license": "BSD-3-Clause",
    "exception": {
      "dependency": "org.example:matchit",
      "matchedVersion": "0.8.4",
      "reason": "Reviewed transitive dependency; declared as MIT AND BSD-3-Clause"
    }
  }
}
```

Existing unconfigured output remains byte-identical unless raw dependency
metadata is newly recognized as an explicit SPDX identifier or expression.
Existing denied/unknown policy objects keep their current fields and values.

The `licensePolicy` summary adds `permittedByException` and `staleExceptions`.
`denied` and `unknown` retain their meanings.

Config-level exception audit results are also rendered even when there is no
matching component row. Text adds a deterministic `License exceptions:` block;
JSON adds a sorted `exceptions` array containing `dependency`, configured
`version`, `status` (`used`, `version-mismatched`, `missing`, or `redundant`),
`resolvedVersion`, `reason`, and `member` for workspace-owned policies. `zolt
licenses` continues to exit zero; the same audit result is a failure under
`zolt check --check license-policy`.

### CycloneDX 1.5

CycloneDX 1.5 defines its license choice as either a list of named/SPDX license
objects or exactly one SPDX expression object. An explicit expression is emitted
as:

```json
"licenses": [
  { "expression": "MIT AND BSD-3-Clause" }
]
```

Discrete Maven records retain the existing list of license objects. Policy
exceptions never alter SBOM evidence: an SBOM says what the package declares,
not what a local policy permits.

CycloneDX 1.5 cannot mix an expression object with ordinary license objects in
one `licenseChoice`. When a POM contains one explicit expression and no other
records, Zolt emits the expression object. When an explicit expression appears
beside any other Maven license record, Zolt emits every record as a named
license object using its preserved raw name/URL. This is schema-valid and does
not invent `OR` between records; expression-aware evaluation and the Zolt-native
report still use the parsed AST.

`[package.metadata].license` uses the same expression parser for root/member SBOM
metadata, but dependency-policy evaluation remains third-party only.

## Module boundaries

- `zolt-model`: `LicensePolicyException`, the additive settings map, and the
  shared `sh.zolt.license` catalog/parser/AST.
- `zolt-toml`: nested exception parsing, validation, and deterministic writing.
- `zolt-sbom`: declaration model, expression-aware POM resolution, evaluation,
  reporting, and CycloneDX output.
- `zolt-quality`: turn evaluation and stale-exception results into check
  pass/warn/fail records with cause-specific remediation.
- `apps/zolt`: keep pairing member policies with member projections; merge
  complete decisions rather than only findings.

`zolt-sbom` does not gain a workspace dependency. No expression or exception is
written to `zolt.lock`; cached POM metadata remains the evidence source.
The source bootstrap copies the `zolt-model` catalog resources into its merged
classes directory, so source-bootstrap, packaged JVM, and native execution share
the same pinned identifiers.

## Verification matrix

1. Parser precedence and canonicalization: nested parentheses, lowercase
   operators, `WITH`, malformed tokens, trailing input, excessive nesting, and
   catalog-unknown IDs.
2. Evaluator truth tables for `AND`, `OR`, and `WITH`, including mixed
   permitted/exception/warn/violation branches and deterministic `OR` ties.
3. Deny precedence: exact term and denied base license under `WITH` cannot be
   rescued by an exception.
4. Exception matching: exact coordinate, optional exact version, wrong version,
   missing dependency, redundant allowance, required reason, and invalid SPDX
   terms.
5. Evidence fixtures: explicit expression in a POM name, multiple ambiguous POM
   records, parent-inherited expression, unmapped expression-shaped text, and
   missing cached POM.
6. Reporting goldens: text `[exception]`, additive JSON, stable ordering, no
   exception annotations on unrelated license rows or outside enforced scopes,
   and byte-identical legacy output when the feature is unused.
7. CycloneDX golden plus validation against the pinned 1.5 schema for expression
   versus discrete-license choices, including the mixed-record named-license
   fallback.
8. Workspace cases: member-local exception, same dependency under conflicting
   member policies, unused exception in one member, and aggregate strictness.
9. End-to-end CLI proof that `zolt licenses` still exits zero while
   `zolt check --check license-policy` enforces the same decisions.

## Delivery slices

1. **Expression foundation:** pinned catalog, parser/AST, declaration model,
   POM mapping, CycloneDX emission, and report rendering. Preserve the existing
   policy outcome for discrete Maven records.
2. **Exception configuration:** model/codec/writer, parse-time validation, exact
   matching, evaluator decision model, stale/redundant audit, and quality-check
   remediation.
3. **Workspace and public contract:** member merge semantics, CLI text/JSON
   goldens, reference documentation, config examples, and full packaged/native
   verification.

Each slice must leave reporting and enforcement on the same evaluator. There is
no interim state where `zolt licenses` claims a decision that `zolt check` does
not enforce.

## Refusals

- No generic suppression, ignore list, policy-disable flag, or exception that
  overrides `deny`.
- No wildcard coordinate, group, version-range, classifier, or artifact-type
  matching.
- No guessing `AND` from multiple Maven `<license>` elements.
- No fuzzy SPDX identifier matching, partial expression parsing, or runtime
  license-list download.
- No widening enforcement to test/provided/dev/tool scopes in this change.
- No exception data in CycloneDX evidence and no lockfile schema change.
- No legal conclusion beyond evaluating declared metadata against configured
  policy.

## Standards references

- [SPDX 3.0.1 license-expression grammar](https://spdx.github.io/spdx-spec/v3.0.1/annexes/spdx-license-expressions/)
- [CycloneDX 1.5 JSON schema](https://github.com/CycloneDX/specification/blob/1.5/schema/bom-1.5.schema.json)
- [Maven POM license metadata](https://maven.apache.org/pom.html#licenses)
