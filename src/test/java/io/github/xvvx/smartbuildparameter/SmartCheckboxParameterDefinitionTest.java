package io.github.xvvx.smartbuildparameter;

import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterValue;
import hudson.util.FormValidation;
import java.util.List;
import net.sf.json.JSONArray;
import org.kohsuke.stapler.StaplerRequest2;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-2 automated cases, see docs/TEST_PLAN.md (TC-FR2-001 … TC-FR2-007).
 */
@WithJenkins
class SmartCheckboxParameterDefinitionTest {

    private static SmartCheckboxParameterDefinition def(String options, String defaultChecked, String delimiter, int minSelected) {
        return new SmartCheckboxParameterDefinition("ENV", options, defaultChecked, delimiter, minSelected, "environments");
    }

    /** TC-FR2-002: joined value preserves selection order; TC-FR2-003: empty selection → empty string. */
    @Test
    void joinsSelectedValuesInSubmissionOrder() {
        SmartCheckboxParameterDefinition d = def("dev\ntest\nprod\n", null, ",", 0);

        JSONObject jo = new JSONObject();
        JSONArray values = new JSONArray();
        values.add("prod");
        values.add("dev");
        jo.put("value", values);
        StringParameterValue v = (StringParameterValue) d.createValue((StaplerRequest2) null, jo);
        assertEquals("prod,dev", v.getValue());

        JSONObject empty = new JSONObject();
        empty.put("value", new JSONArray());
        assertEquals("", ((StringParameterValue) d.createValue((StaplerRequest2) null, empty)).getValue());

        assertEquals("a|b", def("a\nb\n", null, "|", 0).join(List.of("a", "b")));
    }

    /** Option list parsing: blank lines removed, trimmed, order kept (FR-2). */
    @Test
    void parsesOptionList() {
        SmartCheckboxParameterDefinition d = def("  dev \n\n test\n", null, ",", 0);
        assertEquals(List.of("dev", "test"), d.getOptionList());
        assertTrue(d.isDefaultChecked("dev") == false);
    }

    /** Defaults: only valid options are kept, order preserved (FR-2). */
    @Test
    void filtersDefaultCheckedToKnownOptions() {
        SmartCheckboxParameterDefinition d = def("dev\ntest\nprod\n", "test,nope,dev", ",", 0);
        assertEquals(List.of("test", "dev"), d.getDefaultCheckedList());
        assertEquals("test,dev", ((StringParameterValue) d.getDefaultParameterValue()).getValue());
        assertTrue(d.isDefaultChecked("test"));
        assertFalse(d.isDefaultChecked("prod"));
    }

    /** Single-selection submissions arrive as a plain string, not an array (FR-2). */
    @Test
    void acceptsSingleStringValue() {
        JSONObject jo = new JSONObject();
        jo.put("value", "dev");
        assertEquals("dev", ((StringParameterValue) def("dev\ntest", null, ",", 0).createValue((StaplerRequest2) null, jo)).getValue());
    }

    /** TC-FR2-001: job-config roundtrip keeps every field. */
    @Test
    void configRoundtrip(JenkinsRule r) throws Exception {
        SmartCheckboxParameterDefinition d = def("dev\ntest\nprod", "dev,test", ",", 1);
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(d));
        r.configRoundtrip(p);

        ParametersDefinitionProperty prop = p.getProperty(ParametersDefinitionProperty.class);
        assertNotNull(prop);
        SmartCheckboxParameterDefinition after =
                assertInstanceOf(SmartCheckboxParameterDefinition.class, prop.getParameterDefinition("ENV"));
        assertEquals("dev\ntest\nprod", after.getOptions());
        assertEquals("dev,test", after.getDefaultChecked());
        assertEquals(",", after.getDelimiter());
        assertEquals(1, after.getMinSelected());
        assertEquals("environments", after.getDescription());
    }

    /** TC-FR2-006 (增补 A): options containing the delimiter or duplicated are rejected. */
    @Test
    void rejectsOptionsContainingDelimiterOrDuplicates() {
        SmartCheckboxParameterDefinition.DescriptorImpl desc = new SmartCheckboxParameterDefinition.DescriptorImpl();
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckOptions("a,b\nc", ",").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckOptions("a\na\n", ",").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckOptions("  \n\n", ",").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckOptions("a\nb\nc", ",").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckOptions("a,b", "|").kind);
    }

    /** TC-FR2-007: unknown defaults are reported. */
    @Test
    void rejectsUnknownDefaults() {
        SmartCheckboxParameterDefinition.DescriptorImpl desc = new SmartCheckboxParameterDefinition.DescriptorImpl();
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckDefaultChecked("nope", "dev\ntest", ",").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckDefaultChecked("dev,test", "dev\ntest", ",").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckDefaultChecked("", "dev\ntest", ",").kind);
    }

    /** Min-selected bounds (增补 A). */
    @Test
    void validatesMinSelected() {
        SmartCheckboxParameterDefinition.DescriptorImpl desc = new SmartCheckboxParameterDefinition.DescriptorImpl();
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckMinSelected("-1", "a\nb").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckMinSelected("abc", "a\nb").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckMinSelected("3", "a\nb").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckMinSelected("2", "a\nb").kind);
    }

    /**
     * Pipeline contract: the {@code smartCheckbox} symbol is usable from a Pipeline
     * {@code properties(parameters(...))} call (the same mechanism declarative
     * {@code parameters {}} uses underneath). Full declarative E2E runs on the live
     * Jenkins instance (EN-B, TC-FR2-E01).
     */
    @Test
    void pipelineSymbolRegistersParameter(JenkinsRule r) throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "pipe");
        p.setDefinition(new CpsFlowDefinition(
                "properties([parameters([smartCheckbox(name: 'ENV', options: 'dev\\ntest\\nprod', defaultChecked: 'dev', delimiter: ',', minSelected: 1)])])\n"
                        + "echo \"env=${params.ENV}\"", true));
        r.buildAndAssertSuccess(p);
        // The properties step from the first run registers the definition for subsequent runs.
        ParametersDefinitionProperty prop = p.getProperty(ParametersDefinitionProperty.class);
        assertNotNull(prop);
        assertInstanceOf(SmartCheckboxParameterDefinition.class, prop.getParameterDefinition("ENV"));

        // Second run: submit a selection and assert the joined value reaches the build env.
        SmartCheckboxParameterDefinition d =
                assertInstanceOf(SmartCheckboxParameterDefinition.class, prop.getParameterDefinition("ENV"));
        r.assertLogContains("env=", r.buildAndAssertSuccess(p));
        assertEquals("dev", ((StringParameterValue) d.getDefaultParameterValue()).getValue());
    }
}
