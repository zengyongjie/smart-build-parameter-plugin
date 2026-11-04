# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Jenkins plugin (`smart-build-parameter-plugin`, HPI packaging) providing smart build
parameter types for declarative pipelines. **Authoritative specs: `docs/REQUIREMENTS.md`
(FR-1…FR-6, decisions D1–D6) and `docs/TEST_PLAN.md` (TC-* cases) — keep code and docs in
sync.** Implemented: `SmartCheckboxParameterDefinition` (FR-2, symbol `smartCheckbox`),
`SmartBooleanParameterDefinition` (FR-1, symbol `smartBoolean`), and
`SmartSelectParameterDefinition` (FR-3, symbol `smartSelect`) with two sources:
`ShellSelectSource` (symbol `shell`) and `HttpSelectSource` (FR-4, symbol `http`);
`SmartBuildParameterPageDecorator` (FR-5, modernized Build With Parameters page);
FR-6 done: build-history Parameters page modernization via `ParameterValue` subclasses
(`SmartBooleanParameterValue`, `SmartMultiParameterValue`) with their own `value.jelly`
(boolean badge; chips + raw joined string, `title` keeps full value for CSS truncation).

Standard Jenkins plugin stack:

- Parent POM `org.jenkins-ci.plugins:plugin` — supplies HPI packaging, SpotBugs,
  Surefire config, and the managed `jenkins-core` version.
- `io.jenkins.tools.bom:bom-${jenkins.baseline}.x` — manages test/plugin dependency
  versions. The `jenkins.baseline` property (currently 2.528, an LTS line) must stay
  in sync with the BOM artifact and with `jenkins.version` (`${jenkins.baseline}.3`);
  bump all together when updating.

## Toolchain

No JDK or Maven on the host PATH; Docker is available. Build with:

```bash
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$PWD":/work -v "$HOME/.m2":/var/maven -w /work \
  maven:3.9-eclipse-temurin-21 \
  mvn -B -ntp -Dmaven.repo.local=/var/maven/repository <goals>
```

Reuse the host `~/.m2` mount — it already caches Jenkins artifacts from prior work.
If Maven and a JDK (17 or 21) have since been installed on the host, plain `mvn <goals>` works too.

## Common commands

Substitute `<goals>` in the command above (or run directly if Maven is installed):

- `verify` — compile, tests, SpotBugs (static analysis can fail the build)
- `test` — tests only
- `test -Dtest=SmartBooleanParameterDefinitionTest` — single test class; append
  `#configRoundtrip` for a single method
- `hpi:run` — local Jenkins controller at http://localhost:8080/jenkins with the plugin loaded
- `package` — installable `.hpi` in `target/`

Tests boot a real in-memory Jenkins per class (`JenkinsRule` via JUnit 5 `@WithJenkins`);
first runs are slow. A first-ever build downloads a lot of dependencies.

## Architecture

Extension point: parameter definitions extending `SimpleParameterDefinition` (jenkins-core,
`provided` scope) in `src/main/java/io/github/xvvx/smartbuildparameter/`, each with a nested
`DescriptorImpl` annotated `@Extension @Symbol("smart…")` to register in the job-config
"Add Parameter" dropdown and Pipeline `parameters {}`.

- `SmartBooleanParameterDefinition` (FR-1, `smartBoolean`): `BooleanParameterValue` semantics;
  `controls` = comma-separated names of parameters whose visibility it toggles on the build page.
- `SmartCheckboxParameterDefinition` (FR-2, `smartCheckbox`): multi-select; submitted value is
  the selection joined with `delimiter` (order preserved; browser submits list order, remote API
  array order or a pre-joined string).
- `SmartSelectParameterDefinition` (FR-3, `smartSelect`): dynamic dropdown; options come from a
  `SmartSelectSource` subclass (`AbstractDescribableImpl` + `Serializable`; the base owns the TTL
  cache — successes only, `synchronized` single-flight — `cleanOptions`, and a final
  `fetch(forceRefresh)`; subclasses implement `doFetch()`/`cacheTtlSeconds()`), selected in
  job-config's Advanced section via `f:dropdownDescriptorSelector`. Sources:
  `ShellSelectSource` (FR-3, `/bin/sh -c` on the controller) and `HttpSelectSource` (FR-4,
  `java.net.http.HttpClient`, parse modes jsonArray/jsonPath/textLines, credential-store
  basic/bearer auth). Options are evaluated server-side **at build-page render** (`index.jelly`
  stores one `getOptionsOrError()` result in a `j:set` — never call it twice per render); the
  widget (`js/smart-select.js` + `css/smart-select.css`) is progressive enhancement over radio /
  json-attribute checkbox controls; a source failure renders an inline error + Retry and **no
  `value` control** (so the parameter is not submitted). Retry fetches
  `/smart-build-parameter/reloadOptions?job=<fullName>&name=<param>` — a `RootAction`
  (`SmartSelectReloadAction`), NOT a `/descriptorByName/` URL (that route 404s for nested
  `$DescriptorImpl` classes).
- `SmartBuildParameterPageDecorator` (FR-5): a `PageDecorator` whose `header.jelly` appends
  `css/smart-build-page.css` + `js/smart-build-page.js` (defer) when the request URI ends in
  `/build` (core's Build With Parameters form is `hudson/model/ParametersDefinitionProperty/index.jelly`,
  form selector `form[name="parameters"]`). The JS only ADDS classes (`sbpd-modern` on the form,
  `sbpd-card` per `div[name=parameter]`, `sbpd-actions` on the button bar) — never moves nodes,
  so it cannot fight the parameter scripts; the CSS is fully scoped under `.sbpd-modern`.
  Kill switch: system property `io.github.xvvx.smartbuildparameter.modernBuildPage=false`, read
  per render (script-console toggleable); when off, nothing is injected. Note: core's parameter
  submission itself requires core's hudson-behavior.js (buildFormTree) — a fully JS-disabled
  browser cannot submit core's parameter form either; the plugin's guarantee is "plugin resources
  404 / switch off ⇒ exactly the native page, still submittable".
- Data flow: job config (definition) → `createValue(...)` → parameter value →
  `ParametersAction` → build environment variable named after the parameter.
- Build-history Parameters page (FR-6): core's `ParametersAction/index.jelly` includes
  `value.jelly` per value **resolved by runtime type**, so plugin `ParameterValue` subclasses
  (`SmartBooleanParameterValue extends BooleanParameterValue`,
  `SmartMultiParameterValue extends StringParameterValue` — joined `value` inherited, ordered
  `choices` added, NOT `@Exported` so `api/json` keeps the plain name/value shape) get plugin
  views (badge / chips + raw string, `css/smart-values.css`, scoped `.sbpd-values`) while
  native types keep core rendering. `choices` is a plain `ArrayList` — XStream (build.xml
  persistence) cannot construct `List.of()`/immutable collections. `createValue(String)`
  re-splits a pre-joined value with `Pattern.quote(delimiter)` (增补 A makes it unambiguous);
  `createValue(req, jo)` with a STRING `value` (API-posted `json` or a single browser selection)
  must take the same re-split path — stuffing the whole string into `choices` as one element
  renders a giant chip on the history page (bug found by the full-matrix live simulation).
  equals/hashCode must extend the inherited ones when a field is added (SpotBugs
  EQ_DOESNT_OVERRIDE_EQUALS — StringParameterValue overrides equals, ParameterDefinition
  does not, which is why the definition classes skip it).
- Build page (index.jelly) markup follows core: `<div name="parameter">` + hidden
  `name` input + control(s) named `value`. Front-end behavior lives in
  `src/main/webapp/js/smart-params.js` + `js/smart-select.js`
  (served at `/plugin/smart-build-parameter-plugin/js/…`),
  included by each index.jelly; idempotent, progressive enhancement — no core templates are overridden.
- Config forms are Jelly: **use pure field binding**
  `<f:entry title="..." field="x"><f:textbox/></f:entry>` with a bare control — never explicit
  `name=`/`value="${it.x}"` attributes in parameter-definition config forms. In this context `it`
  resolves to the enclosing Project (a `${it.name}` textbox silently captures the *job* name), while
  core's `StringParameterDefinition` `parameter.*` prefix pattern only works for its legacy manual
  `Descriptor.newInstance` binding, not `@DataBoundConstructor`. `configRoundtrip` catches drift.
  Boolean HTML attributes (checked/disabled/…) must render via `${cond ? 'true' : null}` — Jelly
  omits null-valued attributes, but `checked="false"` still *exists*, and HTML attribute presence
  means checked (this once made every smartCheckbox option pre-selected on the build page).
  Enum/boolean fields with a spec default in views: plain radios via `<f:radio name="_.field"
  value="…">` (submits a bare string). Do NOT use `<f:radioBlock>` for plain enums — its
  block-control machinery submits `{"value": "…"}` objects that `@DataBoundConstructor` can't
  bind to a String. For a Boolean field whose spec default is true, keep the `Boolean` object
  type (Pipeline omission → null → default) and expose the view boolean under a DIFFERENT name
  (e.g. `isFilterableEnabled()`): in JEXL `${it.filterable}` binds to `getFilterable()`, which
  returns null, not to `isFilterable()`.
- `help*.html` files next to `config.jelly` provide the inline "?" help; `%key` i18n inside a
  jelly view resolves from `<viewbasename>.properties` / `_zh_CN.properties` beside it, while
  Java-side `Messages*.properties` (package level) feeds the localizer-generated `Messages` class.

## Conventions

- **Structured form submission (`buildFormTree` in hudson-behavior.js) is the contract for the
  build page**: bare checkboxes bind *booleans* — a checkbox that must submit its string value
  needs a `json="${value}"` attribute (unchecked ones are omitted; repeats become a JSONArray).
  Hiding a parameter block = set core's `field-disabled` attribute on its `div[name="parameter"]`
  (controls beneath contribute nothing — that is how "hidden ⇒ not submitted" (D1) is implemented);
  disabling inputs alone does NOT exclude them. `buildWithParameters` (remote API) rejects
  repeated parameter names for every type — multi-select needs the structured-form JSON or a
  pre-joined string. Live-E2E note: POSTing that JSON to `/job/x/build` must be urlencoded —
  `-d 'json={...}&Submit=Build'` (a raw JSON body → 400 "Nothing is submitted"); array values
  (`"value":["a","b"]`) work in the `json` field. On Pipeline, a parameter omitted from the
  submission falls back to its job-config default in `params.X` (workflow-cps fills defaults);
  its ParametersAction/history entry stays absent — D1 is about the *user's choice* not arriving.
- HtmlUnit tests: package is `org.htmlunit.*` (fork), not `com.gargoylesource.htmlunit`;
  `HtmlElement.getAttribute` returns `""` (not null) for missing attributes and there is no
  `getStyle()` — assert on the raw `style` attribute. Enable JS via
  `wc.getOptions().setJavaScriptEnabled(true)` to exercise smart-params.js (HtmlUnit fires
  `click`, not `change`, on checkbox clicks — the script listens to both). Clicking a control
  inside a `display:none` container (e.g. the smartSelect option list before opening the box)
  fires event handlers but does NOT apply the default action — open the box first, then click
  the option. And `r.buildAndAssertSuccess()` schedules a NEW build — after a form submission,
  wait (`r.waitUntilNoActivity()`) and assert on `p.getBuildByNumber(1)` instead.
  `FalsifyingWebConnection` lives in `org.htmlunit.util` (not `org.htmlunit`) here; it is the
  way to simulate plugin-resource 404s while core JS keeps running. When clicking the Build
  button by XPath keep the defensive exclusion `not(contains(@class,'smart-select-confirm'))`
  — multiple-mode select boxes render plain buttons now, but a widget primary button must
  never be mistaken for Build (smart-build-page.js picks the LAST non-widget primary button
  for the `sbpd-actions` hook for the same reason). `JenkinsRule.WebClient.goTo` asserts
  text/html — for JSON URLs use the overload `goTo(url, "application/json")`. Read-only core
  rows on the parameters page render `<pre class="jenkins-readonly">value</pre>`
  (`f:possibleReadOnlyField`), not an input. A submit click that returns the page unchanged
  (e.g. 405 from the initial GET) means JS swallowed the click — log the POST in a
  `FalsifyingWebConnection` wrapper to tell client-side veto from server-side rejection.

- Adding a UI field: constructor param + getter, a `config.jelly` entry, a
  `help-<field>.html`, and extend the `configRoundtrip` test — that test exists to catch
  form/binding drift.
- Java 17 bytecode minimum (parent default). Keep code SpotBugs-clean; `verify` enforces it.
- Baseline API note: `EnvVars` is `hudson.EnvVars` on the 2.528 baseline (moved from `hudson.util.EnvVars`).
  JSON responses: `HttpResponses.okJSON(...)` does not exist on the bundled Stapler — return
  `new org.kohsuke.stapler.json.JsonHttpResponse(jsonObject)` instead (writes the object bare;
  HtmlUnit receives it as `UnexpectedPage`, read via `getInputStream()`). Shell sources:
  drain stdout/stderr on daemon threads (a full pipe deadlocks `waitFor`), bound the kept bytes,
  and `destroyForcibly()` on timeout/over-limit.
- Credentials plugin API (BOM-resolved `org.jenkins-ci.plugins:credentials`, NOT the old
  `com.cloudbees.plugins` groupId in dependency declarations): with Spring Security, use
  `CredentialsProvider.lookupCredentialsInItemGroup(...)` / `lookupCredentialsInItem(...` —
  the deprecated `lookupCredentials` overloads still take `org.acegisecurity.Authentication`
  and won't accept `ACL.SYSTEM2`. `getId()`/`getDescription()` are NOT on the `Credentials`
  interface (use `IdCredentials` / `CredentialsNameProvider.name(c)`). Tests add credentials via
  `CredentialsProvider.lookupStores(j).iterator().next().addCredentials(Domain.global(), ...)`
  wrapped in `ACL.impersonate2(ACL.SYSTEM2, (Runnable) ...)` (the Runnable variant; checked
  exceptions must be rethrown as unchecked inside). Ternary `int`-constant/`Integer` mixing
  reboxes (SpotBugs BX_UNBOXING_IMMEDIATELY_REBOXED) — store `timeoutSeconds`-style fields as
  `int` with null/default normalization in the constructor. Pipeline nested describables need
  symbol-call syntax (`source: http(url: '…')`); a plain map `[type: 'http', ...]` is not resolved.
- The parent POM enforces `access-modifier-checker`: never touch core-class public fields directly
  (e.g. use `StringParameterValue.getValue()`, not `.value`) — `verify` fails on violations.
- Pipeline test deps: the `properties` step comes from **`workflow-multibranch`** (class
  `JobPropertyStep`), `echo` from `workflow-basic-steps`, `sh`/`node` from
  `workflow-durable-task-step`. JenkinsRule only auto-activates plugins directly on the test
  classpath — transitive plugin deps must be declared.
