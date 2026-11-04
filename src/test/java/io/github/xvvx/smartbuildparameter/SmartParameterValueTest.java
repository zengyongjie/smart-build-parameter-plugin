package io.github.xvvx.smartbuildparameter;

import hudson.model.BooleanParameterDefinition;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlTextInput;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-6 automated cases, see docs/TEST_PLAN.md (TC-FR6-001 … TC-FR6-003). A mixed build is
 * submitted through the real Build With Parameters form, then the build-history Parameters
 * page and {@code api/json} are asserted: plugin value types resolve to plugin views
 * (boolean badge, chips + raw joined string, long-value truncation via title), native
 * parameters keep core rendering, and the API shape stays Jenkins-conventional.
 */
@WithJenkins
class SmartParameterValueTest {

    /** 240+-char option value: truncation contract = full text on hover (title attribute). */
    private static final String LONG = "branch-feature-smart-build-parameter-plugin-" + "x".repeat(240);

    // ---------------------------------------------------------------- mixed-job fixture

    private static FreeStyleProject mixedJob(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("NATIVE_STR", "native-str", "native"),
                new BooleanParameterDefinition("NATIVE_B", true, "native"),
                new SmartBooleanParameterDefinition("TOGGLE", true, "", null),
                new SmartCheckboxParameterDefinition("CK", "short\n" + LONG + "\nz", "short", ",", 0, null),
                new SmartSelectParameterDefinition("SEL_S", "single", true, "v1", ",",
                        new ShellSelectSource("printf 'v1\\nv2\\n'", null, null, null), null),
                new SmartSelectParameterDefinition("SEL_M", "multiple", true, "", ",",
                        new ShellSelectSource("printf 'm1\\nm2\\nm3\\n'", null, null, null), null)));
        p.getBuildersList().add(new hudson.tasks.Shell(
                "echo \"GOT TOGGLE=$TOGGLE CK=$CK SEL_S=$SEL_S SEL_M=$SEL_M NATIVE_STR=$NATIVE_STR NATIVE_B=$NATIVE_B\""));
        return p;
    }

    /** Submits the build form with a specific mixed selection; returns build #1. */
    private static FreeStyleBuild submitMixedAndBuild(JenkinsRule r, FreeStyleProject p) throws Exception {
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.setThrowExceptionOnFailingStatusCode(false); // the build form answers 405 on purpose
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");

            ((HtmlTextInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='NATIVE_STR']]//input[@type='text']"))
                    .setValue("native-str");
            // TOGGLE stays at its default true; NATIVE_B stays true.
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='CK']]//input[@type='checkbox' and @value='" + LONG + "']"))
                    .click();
            // smartSelect single: open the box first (the list starts display:none — a click on a
            // hidden control fires handlers but not the default action), then pick v2.
            ((HtmlElement) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL_S']]//button[contains(@class,'smart-select-box')]"))
                    .click();
            ((HtmlRadioButtonInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL_S']]//input[@type='radio' and @value='v2']"))
                    .click();
            assertTrue(((HtmlRadioButtonInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL_S']]//input[@type='radio' and @value='v2']"))
                    .isChecked(), "radio v2 is checked after the click");
            // smartSelect multiple: open, tick m2 and m3 (list order ⇒ joined "m2,m3").
            ((HtmlElement) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL_M']]//button[contains(@class,'smart-select-box')]"))
                    .click();
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL_M']]//input[@type='checkbox' and @value='m2']"))
                    .click();
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL_M']]//input[@type='checkbox' and @value='m3']"))
                    .click();

            // Defensive: never click a primary button inside a smartSelect widget — the real
            // Build button is the form's last primary button (multiple-mode select boxes are
            // plain buttons, but keep the exclusion so a widget button can never win).
            HtmlElement build = (HtmlElement) page.getFirstByXPath(
                    "//form[@name='parameters']//button[contains(@class,'jenkins-button--primary')"
                            + " and not(contains(@class,'smart-select-confirm'))]");
            build.click();

            r.waitUntilNoActivity();
            FreeStyleBuild b = p.getBuildByNumber(1);
            assertNotNull(b, "form submission queued a build");
            String log = b.getLog();
            assertTrue(log.contains("TOGGLE=true"), log);
            assertTrue(log.contains("CK=short," + LONG), log);
            assertTrue(log.contains("SEL_S=v2"), log);
            assertTrue(log.contains("SEL_M=m2,m3"), log);
            assertTrue(log.contains("NATIVE_STR=native-str"), log);
            assertTrue(log.contains("NATIVE_B=true"), log);
            return b;
        }
    }

    // ---------------------------------------------------------------- TC-FR6-001 + TC-FR6-002

    /**
     * TC-FR6-001: the build Parameters page resolves plugin views for the plugin value types
     * (view resolution is by runtime type) — badge for smartBoolean, chips + raw string for
     * smartCheckbox / smartSelect. TC-FR6-002: chip structure and the original joined string;
     * long values truncate with the full value on hover (title).
     */
    @Test
    void parametersPageShowsBadgesChipsAndRawValues(JenkinsRule r) throws Exception {
        FreeStyleProject p = mixedJob(r);
        submitMixedAndBuild(r, p);

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo("job/" + p.getName() + "/1/parameters/");

            // The plugin stylesheet for value presentation is present.
            assertTrue(page.getByXPath("//link[contains(@href,'smart-values.css')]").size() >= 1);

            // smartBoolean → true badge.
            HtmlElement badge = (HtmlElement) page.getFirstByXPath("//span[contains(@class,'sbpd-badge')]");
            assertNotNull(badge, "boolean badge rendered");
            assertTrue(badge.getAttribute("class").contains("sbpd-badge--true"));
            assertEquals("true", badge.getTextContent());

            // chips: one per selected value across CK (short, LONG), SEL_S (v2), SEL_M (m2, m3).
            List<String> chipTexts = new ArrayList<>();
            for (Object n : page.getByXPath("//span[contains(@class,'sbpd-chip')]")) {
                chipTexts.add(((HtmlElement) n).getTextContent());
            }
            assertEquals(List.of("short", LONG, "v2", "m2", "m3"), chipTexts);

            // truncation contract: the long chip keeps the full value in its title attribute.
            HtmlElement longChip = (HtmlElement) page.getFirstByXPath(
                    "//span[contains(@class,'sbpd-chip') and @title='" + LONG + "']");
            assertNotNull(longChip, "long chip carries the full value on hover");

            // raw joined strings next to the chips (D2), also truncation-safe via title.
            List<String> rawTexts = new ArrayList<>();
            for (Object n : page.getByXPath("//code[contains(@class,'sbpd-raw')]")) {
                rawTexts.add(((HtmlElement) n).getTextContent());
            }
            java.util.Collections.sort(rawTexts);
            assertEquals(List.of("m2,m3", "short," + LONG, "v2"), rawTexts);
            assertNotNull(page.getFirstByXPath("//code[contains(@class,'sbpd-raw') and @title='short," + LONG + "']"));

            // Native rows keep core markup (readOnly f:textbox → <pre class="jenkins-readonly">):
            // exactly the four smart values carry plugin classes.
            assertNotNull(page.getFirstByXPath("//pre[contains(@class,'jenkins-readonly') and text()='native-str']"),
                    "native String row keeps core's readonly rendering");
            assertEquals(4, page.getByXPath("//*[contains(@class,'sbpd-values')]").size(),
                    "only the four smart values use plugin presentation markup");
        }
    }

    // ---------------------------------------------------------------- TC-FR6-003

    /** TC-FR6-003: build api/json keeps the Jenkins parameter conventions (name/value pairs). */
    @Test
    void apiJsonKeepsJenkinsConventions(JenkinsRule r) throws Exception {
        FreeStyleProject p = mixedJob(r);
        submitMixedAndBuild(r, p);

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            Page page = wc.goTo("job/" + p.getName() + "/1/api/json", "application/json");
            String body = page.getWebResponse().getContentAsString();
            Map<String, Object> params = new HashMap<>();
            for (Object a : JSONObject.fromObject(body).getJSONArray("actions")) {
                JSONObject action = (JSONObject) a;
                if (action.has("parameters")) {
                    for (Object pv : action.getJSONArray("parameters")) {
                        JSONObject o = (JSONObject) pv;
                        params.put(o.getString("name"), o.get("value"));
                    }
                }
            }
            assertEquals(Boolean.TRUE, params.get("TOGGLE"), "boolean exports as JSON true");
            assertEquals("short," + LONG, params.get("CK"), "multi-select exports the joined string");
            assertEquals("v2", params.get("SEL_S"));
            assertEquals("m2,m3", params.get("SEL_M"));
            assertEquals("native-str", params.get("NATIVE_STR"));
            assertEquals(Boolean.TRUE, params.get("NATIVE_B"));
            assertTrue(params.size() == 6, "every submitted parameter is exported");
            assertTrue(!body.contains("choices"),
                    "chip data is presentation-only and never leaks into the API shape");
        }
    }

    // ---------------------------------------------------------------- empty selection rendering

    /** An empty multi selection renders the empty marker instead of an empty chip row. */
    @Test
    void emptySelectionRendersEmptyMarker(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                new SmartCheckboxParameterDefinition("CK", "a\nb\n", null, ",", 0, null)));
        p.getBuildersList().add(new hudson.tasks.Shell("echo \"GOT CK=[$CK]\""));

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");
            // Defensive: never click a primary button inside a smartSelect widget — the real
            // Build button is the form's last primary button (multiple-mode select boxes are
            // plain buttons, but keep the exclusion so a widget button can never win).
            HtmlElement build = (HtmlElement) page.getFirstByXPath(
                    "//form[@name='parameters']//button[contains(@class,'jenkins-button--primary')"
                            + " and not(contains(@class,'smart-select-confirm'))]");
            build.click();
            r.waitUntilNoActivity();
            FreeStyleBuild b = p.getBuildByNumber(1);
            assertNotNull(b);
            assertTrue(b.getLog().contains("GOT CK=[]"), b.getLog());

            HtmlPage params = wc.goTo("job/" + p.getName() + "/1/parameters/");
            assertEquals(0, params.getByXPath("//span[contains(@class,'sbpd-chip')]").size());
            HtmlElement empty = (HtmlElement) params.getFirstByXPath("//code[contains(@class,'sbpd-raw--empty')]");
            assertNotNull(empty, "empty selection shows the empty marker");
            assertEquals("(empty)", empty.getTextContent());
        }
    }

    // ---------------------------------------------------------------- unit semantics

    /** createValue paths produce SmartMulti/SmartBoolean values with the split selection kept. */
    @Test
    void valueSemanticsUnit() {
        SmartCheckboxParameterDefinition ck = new SmartCheckboxParameterDefinition("CK", "a\nb\nc", "a,b", ",", 0, null);
        SmartMultiParameterValue joined = assertInstanceOf(SmartMultiParameterValue.class, ck.createValue("a,b,c"));
        assertEquals("a,b,c", joined.getValue());
        assertEquals(List.of("a", "b", "c"), joined.getChoices());

        // regex metachar delimiter ⇒ Pattern.quote keeps the split literal.
        SmartCheckboxParameterDefinition pipe = new SmartCheckboxParameterDefinition("P", "x\ny", "x", "|", 0, null);
        SmartMultiParameterValue split = assertInstanceOf(SmartMultiParameterValue.class, pipe.createValue("x|y"));
        assertEquals(List.of("x", "y"), split.getChoices());

        SmartMultiParameterValue empty = assertInstanceOf(SmartMultiParameterValue.class, pipe.createValue(""));
        assertEquals("", empty.getValue());
        assertTrue(empty.getChoices().isEmpty());

        SmartMultiParameterValue def = assertInstanceOf(SmartMultiParameterValue.class, ck.getDefaultParameterValue());
        assertEquals("a,b", def.getValue());
        assertEquals(List.of("a", "b"), def.getChoices());

        SmartSelectParameterDefinition single = new SmartSelectParameterDefinition("S", "single", true, "v2", ",",
                new ShellSelectSource("printf 'v1\\nv2\\n'", null, null, null), null);
        SmartMultiParameterValue sd = assertInstanceOf(SmartMultiParameterValue.class, single.getDefaultParameterValue());
        assertEquals("v2", sd.getValue());
        assertEquals(List.of("v2"), sd.getChoices());

        SmartSelectParameterDefinition multi = new SmartSelectParameterDefinition("M", "multiple", true, "m2 , m1,m2", ",",
                new ShellSelectSource("printf 'm1\\nm2\\nm3\\n'", null, null, null), null);
        SmartMultiParameterValue md = assertInstanceOf(SmartMultiParameterValue.class, multi.getDefaultParameterValue());
        assertEquals("m2,m1", md.getValue());
        assertEquals(List.of("m2", "m1"), md.getChoices());

        // Structured-form path receiving a pre-joined STRING value (API-driven `json`
        // submissions): chips must be re-split on the delimiter, not one giant chip
        // (regression found by the full-matrix live simulation, 2026-11-04).
        org.kohsuke.stapler.StaplerRequest2 nullReq = null; // cast needed: core also has a deprecated StaplerRequest overload
        SmartMultiParameterValue joinedForm = assertInstanceOf(SmartMultiParameterValue.class,
                ck.createValue(nullReq, JSONObject.fromObject("{\"value\":\"a,b,c\"}")));
        assertEquals("a,b,c", joinedForm.getValue());
        assertEquals(List.of("a", "b", "c"), joinedForm.getChoices());

        SmartMultiParameterValue pipeForm = assertInstanceOf(SmartMultiParameterValue.class,
                pipe.createValue(nullReq, JSONObject.fromObject("{\"value\":\"x|y\"}")));
        assertEquals(List.of("x", "y"), pipeForm.getChoices());

        // a plain single selection stays exactly one chip
        SmartMultiParameterValue singleForm = assertInstanceOf(SmartMultiParameterValue.class,
                ck.createValue(nullReq, JSONObject.fromObject("{\"value\":\"b\"}")));
        assertEquals("b", singleForm.getValue());
        assertEquals(List.of("b"), singleForm.getChoices());

        SmartBooleanParameterDefinition sb = new SmartBooleanParameterDefinition("T", true, "", null);
        assertInstanceOf(SmartBooleanParameterValue.class, sb.createValue("true"));
        assertInstanceOf(SmartBooleanParameterValue.class, sb.getDefaultParameterValue());
    }

    /** Persisted values (build.xml via XStream2) keep the chip data — replay/rebuild renders it. */
    @Test
    void persistedValuesSurviveXStreamRoundTrip() {
        SmartMultiParameterValue v = new SmartMultiParameterValue("CK", "a,b", List.of("a", "b"), "desc");
        Object back = Run.XSTREAM2.fromXML(Run.XSTREAM2.toXML(v));
        SmartMultiParameterValue restored = assertInstanceOf(SmartMultiParameterValue.class, back);
        assertEquals("a,b", restored.getValue());
        assertEquals(List.of("a", "b"), restored.getChoices());

        SmartBooleanParameterValue b = new SmartBooleanParameterValue("T", true, "d");
        SmartBooleanParameterValue restoredB = assertInstanceOf(SmartBooleanParameterValue.class,
                Run.XSTREAM2.fromXML(Run.XSTREAM2.toXML(b)));
        assertTrue(restoredB.getValue());
    }
}
