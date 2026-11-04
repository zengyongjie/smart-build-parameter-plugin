package io.github.xvvx.smartbuildparameter;

import hudson.model.BooleanParameterDefinition;
import hudson.model.BooleanParameterValue;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.util.FormValidation;
import java.util.List;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-1 automated cases, see docs/TEST_PLAN.md (TC-FR1-001 … TC-FR1-008).
 */
@WithJenkins
class SmartBooleanParameterDefinitionTest {

    private static SmartBooleanParameterDefinition def(boolean defaultValue, String controls) {
        return new SmartBooleanParameterDefinition("DEBUG", defaultValue, controls, "toggle");
    }

    /** Control list parsing: trimmed, blanks skipped, deduplicated. */
    @Test
    void parsesControlList() {
        assertEquals(List.of("A", "B"), def(false, " A, B ,,A,").getControlList());
        assertTrue(def(true, "  , ").getControlList().isEmpty());
    }

    /** Value semantics match the native boolean parameter (FR-1 item 4). */
    @Test
    void createValueSemantics() {
        JSONObject on = new JSONObject();
        on.put("value", true);
        BooleanParameterValue v = (BooleanParameterValue) def(true, "X").createValue((StaplerRequest2) null, on);
        assertTrue(v.getValue());

        JSONObject off = new JSONObject();
        off.put("value", false);
        assertFalse(((BooleanParameterValue) def(true, "X").createValue((StaplerRequest2) null, off)).getValue());

        // CLI / remote-API path: lenient parse like core's BooleanParameterDefinition.
        assertTrue(((BooleanParameterValue) def(true, "X").createValue("true")).getValue());
        assertFalse(((BooleanParameterValue) def(true, "X").createValue("nope")).getValue());
        assertTrue(((BooleanParameterValue) def(true, "X").getDefaultParameterValue()).getValue());
        assertFalse(((BooleanParameterValue) def(false, "X").getDefaultParameterValue()).getValue());
    }

    /** TC-FR1-004 (config part): job-config roundtrip keeps every field. */
    @Test
    void configRoundtrip(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                def(false, "TARGET"),
                new StringParameterDefinition("TARGET", "fallback", "controlled")));
        r.configRoundtrip(p);

        ParametersDefinitionProperty prop = p.getProperty(ParametersDefinitionProperty.class);
        SmartBooleanParameterDefinition after = assertInstanceOf(
                SmartBooleanParameterDefinition.class, prop.getParameterDefinition("DEBUG"));
        assertFalse(after.isDefaultValue());
        assertEquals("TARGET", after.getControls());
        assertEquals("toggle", after.getDescription());
    }

    /** TC-FR1-006: unknown or duplicated names in `controls` are warned about, empty is fine. */
    @Test
    void validatesControls() {
        SmartBooleanParameterDefinition.DescriptorImpl d = new SmartBooleanParameterDefinition.DescriptorImpl();
        assertEquals(FormValidation.Kind.OK, d.doCheckControls("", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckControls("A,B", null).kind); // no job context → cannot cross-check
        assertEquals(FormValidation.Kind.WARNING, d.doCheckControls("A,A", null).kind); // duplicate is detectable anywhere
    }

    /** TC-FR1-001/002/003, D1: hidden = field-disabled + not submitted; checked = submitted. */
    @Test
    void hiddenParameterIsNotSubmitted(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                def(false, "TARGET"),
                new StringParameterDefinition("TARGET", "fallback", "controlled")));

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            // The build-parameter form renders normally but answers 405 on purpose
            // (core: "machine agents don't confuse this form with successful build triggering").
            wc.setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");

            HtmlElement targetBlock = parameterBlock(page, "TARGET");
            assertEquals("true", targetBlock.getAttribute("field-disabled"), "hidden block must carry field-disabled");
            assertTrue(targetBlock.getAttribute("style").contains("none"), "hidden block must be display:none");
            // The WHOLE entry row (f:entry title + description) hides too — hiding only the
            // control cell would leave an empty titled row on the page.
            HtmlElement targetRow = (HtmlElement) targetBlock.getFirstByXPath(
                    "ancestor::div[contains(@class,'jenkins-form-item')][1]");
            assertNotNull(targetRow, "parameter sits inside an f:entry row");
            assertTrue(targetRow.getAttribute("style").contains("none"), "entry row hidden as well");

            // The enhancement script itself must have resolved (guards the resURL include).
            assertTrue(page.getElementsByTagName("script").stream()
                    .anyMatch(e -> e.getAttribute("src").contains("smart-params.js")));

            // Toggle on → visible, no field-disabled.
            HtmlCheckBoxInput box = controllerBox(page);
            box.click();
            targetBlock = parameterBlock(page, "TARGET");
            assertFalse(targetBlock.hasAttribute("field-disabled"));
            assertFalse(targetBlock.getAttribute("style").contains("none"));
            assertFalse(targetRow.getAttribute("style").contains("none"), "entry row visible again");

            // Toggle off again → hidden; submit in that state.
            box.click();
            assertEquals("true", parameterBlock(page, "TARGET").getAttribute("field-disabled"));
            submitBuildForm(page);

            r.waitUntilNoActivity();
            ParametersAction action = p.getLastBuild().getAction(ParametersAction.class);
            assertNotNull(action);
            assertNotNull(action.getParameter("DEBUG"), "controller itself is submitted");
            assertFalse(action.getParameter("DEBUG") instanceof BooleanParameterValue bp && bp.getValue());
            assertNull(action.getParameter("TARGET"), "D1: hidden parameter must not be submitted");
        }
    }

    /** TC-FR1-003: with the controller checked, the controlled parameter is submitted. */
    @Test
    void controlledParameterSubmittedWhenChecked(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                def(true, "TARGET"),
                new StringParameterDefinition("TARGET", "fallback", "controlled")));

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            // The build-parameter form renders normally but answers 405 on purpose
            // (core: "machine agents don't confuse this form with successful build triggering").
            wc.setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");
            assertFalse(parameterBlock(page, "TARGET").hasAttribute("field-disabled"), "default checked → visible");
            submitBuildForm(page);

            r.waitUntilNoActivity();
            ParametersAction action = p.getLastBuild().getAction(ParametersAction.class);
            assertNotNull(action);
            BooleanParameterValue controller = assertInstanceOf(BooleanParameterValue.class, action.getParameter("DEBUG"));
            assertTrue(controller.getValue());
            StringParameterValue target = assertInstanceOf(StringParameterValue.class, action.getParameter("TARGET"));
            assertEquals("fallback", target.getValue());
        }
    }

    /** TC-FR1-005: coexists with the native boolean parameter on the same page. */
    @Test
    void coexistsWithNativeBoolean(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                new BooleanParameterDefinition("NATIVE", true, "native one"),
                def(false, "TARGET"),
                new StringParameterDefinition("TARGET", "x", "controlled")));

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            // The build-parameter form renders normally but answers 405 on purpose
            // (core: "machine agents don't confuse this form with successful build triggering").
            wc.setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");
            assertNotNull(parameterBlock(page, "NATIVE"));
            assertEquals("true", parameterBlock(page, "TARGET").getAttribute("field-disabled"));
            submitBuildForm(page);

            r.waitUntilNoActivity();
            ParametersAction action = p.getLastBuild().getAction(ParametersAction.class);
            BooleanParameterValue nativeValue = assertInstanceOf(BooleanParameterValue.class, action.getParameter("NATIVE"));
            assertTrue(nativeValue.getValue(), "native boolean unaffected");
            assertNull(action.getParameter("TARGET"));
        }
    }

    /**
     * TC-FR1-008 (增补 B) + FR-2 item 3: a hidden smartCheckbox with minSelected never blocks
     * submission; once visible with too few selections, submission is vetoed client-side.
     */
    @Test
    void minSelectedExemptWhenHiddenAndEnforcedWhenVisible(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                def(false, "SVCS"),
                new SmartCheckboxParameterDefinition("SVCS", "a\nb\n", null, ",", 1, null)));

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            // The build-parameter form renders normally but answers 405 on purpose
            // (core: "machine agents don't confuse this form with successful build triggering").
            wc.setThrowExceptionOnFailingStatusCode(false);
            final var alerts = new java.util.ArrayList<String>();
            wc.setAlertHandler((page, message) -> alerts.add(message));

            // Hidden → exempt: submission goes through although nothing is selected (min=1).
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");
            assertEquals("true", parameterBlock(page, "SVCS").getAttribute("field-disabled"));
            submitBuildForm(page);
            r.waitUntilNoActivity();
            assertEquals(1, p.getLastBuild().getNumber(), "hidden group must not block submission");
            assertNull(p.getLastBuild().getAction(ParametersAction.class).getParameter("SVCS"));

            // Visible (controller checked) with 0 of min 1 → vetoed: alert shown, no new build.
            page = wc.goTo("job/" + p.getName() + "/build");
            controllerBox(page).click();
            assertFalse(parameterBlock(page, "SVCS").hasAttribute("field-disabled"));
            submitBuildForm(page);
            r.waitUntilNoActivity();
            assertEquals(1, p.getLastBuild().getNumber(), "vetoed submission must not schedule a build");
            assertFalse(alerts.isEmpty(), "minSelected veto must alert the user");

            // Selecting the minimum lifts the veto, and the submitted value is the option
            // string (checkbox `json` attribute), not a boolean.
            page = wc.goTo("job/" + p.getName() + "/build");
            controllerBox(page).click();
            HtmlCheckBoxInput option = page.getFirstByXPath(
                    "//div[contains(@class,'smart-param-checkbox-group')]//input[@type='checkbox' and @json='a']");
            assertNotNull(option, "option checkbox with json attribute not found");
            option.setChecked(true);
            alerts.clear();
            submitBuildForm(page);
            r.waitUntilNoActivity();
            assertEquals(2, p.getLastBuild().getNumber(), "compliant submission must schedule a build");
            StringParameterValue svcs = assertInstanceOf(StringParameterValue.class,
                    p.getLastBuild().getAction(ParametersAction.class).getParameter("SVCS"));
            assertEquals("a", svcs.getValue());
            assertTrue(alerts.isEmpty());
        }
    }

    /** TC-FR1-004: the `smartBoolean` symbol registers from a Pipeline properties() call. */
    @Test
    void pipelineSymbolRegistersParameter(JenkinsRule r) throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "pipe-bool");
        p.setDefinition(new CpsFlowDefinition(
                "properties([parameters([smartBoolean(name: 'DEBUG', defaultValue: true, controls: 'X')])])\n"
                        + "echo \"debug=${params.DEBUG}\"", true));
        r.buildAndAssertSuccess(p);

        ParametersDefinitionProperty prop = p.getProperty(ParametersDefinitionProperty.class);
        assertNotNull(prop);
        SmartBooleanParameterDefinition d = assertInstanceOf(
                SmartBooleanParameterDefinition.class, prop.getParameterDefinition("DEBUG"));
        assertTrue(d.isDefaultValue());
        assertTrue(((BooleanParameterValue) d.getDefaultParameterValue()).getValue());
        r.assertLogContains("debug=true", r.buildAndAssertSuccess(p));
    }

    // ---------------------------------------------------------------- helpers

    private static HtmlElement parameterBlock(HtmlPage page, String name) {
        String xpath = "//div[@name='parameter'][.//input[@name='name' and @value='" + name + "']]";
        HtmlElement block = page.getFirstByXPath(xpath);
        assertNotNull(block, "parameter block not found: " + name);
        return block;
    }

    private static HtmlCheckBoxInput controllerBox(HtmlPage page) {
        HtmlCheckBoxInput box = page.getFirstByXPath("//span[@data-smart-param='boolean']//input[@type='checkbox']");
        assertNotNull(box, "smartBoolean checkbox not found");
        return box;
    }

    private static void submitBuildForm(HtmlPage page) throws Exception {
        // Core's build button carries no explicit type attribute (default submit type).
        var button = (HtmlElement) page.getFirstByXPath(
                "//form[@name='parameters']//button[contains(@class,'jenkins-button--primary')]");
        if (button == null) {
            button = (HtmlElement) page.getFirstByXPath("//form[@name='parameters']//input[@type='submit']");
        }
        assertNotNull(button, "build button not found");
        button.click();
    }
}
