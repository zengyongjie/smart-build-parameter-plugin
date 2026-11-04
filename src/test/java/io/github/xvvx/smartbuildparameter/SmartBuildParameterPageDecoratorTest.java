package io.github.xvvx.smartbuildparameter;

import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextInput;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-5 automated cases, see docs/TEST_PLAN.md (TC-FR5-001 … TC-FR5-003). A mixed job (native
 * String/Boolean/Choice + every smart type) shares one Build With Parameters page; the plugin
 * may only APPEND its own scoped resources there.
 */
@WithJenkins
class SmartBuildParameterPageDecoratorTest {

    /** Mixed parameter set: the full native trio plus all three smart types. */
    private static FreeStyleProject mixedJob(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("STR", "default-str", ""),
                new BooleanParameterDefinition("FLAG", false, ""),
                new ChoiceParameterDefinition("CH", new String[]{"DEV", "PROD"}, ""),
                new SmartBooleanParameterDefinition("TOGGLE", true, "", ""),
                new SmartCheckboxParameterDefinition("CK", "a\nb\nc", "a,b", ",", 0, ""),
                new SmartSelectParameterDefinition("SEL", "single", true, "a", ",",
                        new ShellSelectSource("printf 'a\\nb'", null, null, null), "")));
        p.getBuildersList().add(new hudson.tasks.Shell(
                "echo \"GOT STR=$STR FLAG=$FLAG CH=$CH TOGGLE=$TOGGLE CK=$CK SEL=$SEL\""));
        return p;
    }

    private static HtmlPage openBuildPage(JenkinsRule.WebClient wc, FreeStyleProject p) throws Exception {
        wc.setThrowExceptionOnFailingStatusCode(false); // the build form answers 405 on purpose
        return wc.goTo("job/" + p.getName() + "/build");
    }

    // ---------------------------------------------------------------- TC-FR5-001

    /** TC-FR5-001: scoped injection on the build page only; core rendering untouched. */
    @Test
    void buildPageInjectsScopedResources(JenkinsRule r) throws Exception {
        FreeStyleProject p = mixedJob(r);
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            HtmlPage page = openBuildPage(wc, p);

            // Exactly one injected stylesheet + one script, namespace-owned.
            assertEquals(1, page.getByXPath("//link[contains(@href,'smart-build-page.css')]").size(),
                    "plugin stylesheet injected once");
            assertEquals(1, page.getByXPath("//script[contains(@src,'smart-build-page.js')]").size(),
                    "plugin script injected once");

            // Marker structure: modern form + one card per parameter + actions hook.
            assertNotNull(page.getFirstByXPath("//form[@name='parameters' and contains(@class,'sbpd-modern')]"),
                    "form carries the modern marker class");
            assertEquals(6, page.getByXPath("//div[@name='parameter' and contains(@class,'sbpd-card')]").size(),
                    "every parameter (native and smart) becomes a card");
            assertNotNull(page.getFirstByXPath(
                    "//form[@name='parameters']//*[contains(@class,'sbpd-actions')]"), "actions bar hook");
            // The hook must land on the REAL Build/Cancel bar, never inside a smartSelect
            // widget panel (the old "first primary button" grab wrecked the dropdown layout).
            assertEquals(0, page.getByXPath(
                    "//div[@data-smart-param='select']//*[contains(@class,'sbpd-actions')]").size(),
                    "actions hook never inside a smartSelect widget");
            assertEquals(0, page.getByXPath("//button[contains(@class,'smart-select-confirm')]").size(),
                    "no widget confirm button (selections apply live)");

            // Core rendering untouched: the native controls are all still there.
            assertNotNull(page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='STR']]//input[@type='text']"));
            assertNotNull(page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='FLAG']]//input[@type='checkbox']"));
            assertNotNull(page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='CH']]//select"));

            // Other pages get nothing: the job top page has no plugin page resources.
            HtmlPage top = wc.goTo("job/" + p.getName() + "/");
            assertEquals(0, top.getByXPath("//link[contains(@href,'smart-build-page.css')]").size());
            assertEquals(0, top.getByXPath("//script[contains(@src,'smart-build-page.js')]").size());
        }
    }

    /** 验收标准 2: a mixed page (native + all smart types) fills and submits end-to-end with JS on. */
    @Test
    void mixedPageSubmitsAllTypesWithJs(JenkinsRule r) throws Exception {
        FreeStyleProject p = mixedJob(r);
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            HtmlPage page = openBuildPage(wc, p);

            ((HtmlTextInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='STR']]//input[@type='text']"))
                    .setValue("native-str");
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='FLAG']]//input[@type='checkbox']"))
                    .click();
            ((HtmlSelect) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='CH']]//select"))
                    .setSelectedAttribute("PROD", true);
            // smartBoolean renders as one checkbox (default checked here): untick → false.
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='TOGGLE']]//input[@type='checkbox']"))
                    .click();
            // smartCheckbox: default-checked {a,b}; untick a, tick c → {b,c} in list order.
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='CK']]//input[@type='checkbox' and @value='a']"))
                    .click();
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='CK']]//input[@type='checkbox' and @value='c']"))
                    .click();
            // smartSelect: open the dropdown first — its option list starts display:none, and
            // (as a real browser) HtmlUnit only applies the click's default action on visible
            // controls. Then pick b.
            ((HtmlElement) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL']]//button[contains(@class,'smart-select-box')]"))
                    .click();
            ((HtmlRadioButtonInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL']]//input[@type='radio' and @value='b']"))
                    .click();
            assertTrue(((HtmlRadioButtonInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='SEL']]//input[@type='radio' and @value='b']"))
                    .isChecked(), "radio b is checked after the click");

            HtmlElement build = (HtmlElement) page.getFirstByXPath(
                    "//form[@name='parameters']//button[contains(@class,'jenkins-button--primary')]");
            build.click();

            // The submitted form IS build #1 — do not schedule another one.
            r.waitUntilNoActivity();
            hudson.model.FreeStyleBuild b = p.getBuildByNumber(1);
            assertNotNull(b, "form submission queued a build");
            String log = b.getLog();
            assertTrue(log.contains("GOT STR=native-str FLAG=true CH=PROD TOGGLE=false CK=b,c SEL=b"), log);
        }
    }

    // ---------------------------------------------------------------- TC-FR5-002

    /** TC-FR5-002: the switch (system property) turns injection off — native DOM, nothing appended. */
    @Test
    void switchDisablesInjection(JenkinsRule r) throws Exception {
        FreeStyleProject p = mixedJob(r);
        System.setProperty(SmartBuildParameterPageDecorator.ENABLED_PROPERTY, "false");
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            HtmlPage off = openBuildPage(wc, p);
            assertEquals(0, off.getByXPath("//link[contains(@href,'smart-build-page.css')]").size(),
                    "no plugin stylesheet when disabled");
            assertEquals(0, off.getByXPath("//script[contains(@src,'smart-build-page.js')]").size(),
                    "no plugin script when disabled");
            assertNull(off.getFirstByXPath("//form[contains(@class,'sbpd-modern')]"),
                    "form stays native");
            assertEquals(0, off.getByXPath("//div[contains(@class,'sbpd-card')]").size());
        } finally {
            System.clearProperty(SmartBuildParameterPageDecorator.ENABLED_PROPERTY);
        }

        // Re-enabled: injection returns (read per render, no restart needed).
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            HtmlPage on = openBuildPage(wc, p);
            assertEquals(1, on.getByXPath("//link[contains(@href,'smart-build-page.css')]").size());
            assertNotNull(on.getFirstByXPath("//form[contains(@class,'sbpd-modern')]"));
        }
    }

    // ---------------------------------------------------------------- TC-FR5-003

    /**
     * TC-FR5-003 / 验收标准 4: the form never depends on THIS plugin's JS. Core's own
     * hudson-behavior.js stays active (core's parameter submission is built on it); every
     * request below this plugin's resource space answers 404 — the enhancement never runs,
     * the page stays native, and the submission still succeeds. (A fully JS-disabled browser
     * cannot submit core's parameter form at all — that is core behavior, not ours to fix.)
     */
    @Test
    void pluginResource404KeepsFormSubmittable(JenkinsRule r) throws Exception {
        FreeStyleProject p = mixedJob(r);
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.setWebConnection(new org.htmlunit.util.FalsifyingWebConnection(wc) {
                @Override
                public org.htmlunit.WebResponse getResponse(org.htmlunit.WebRequest request) throws java.io.IOException {
                    if (request.getUrl().getPath().contains("/plugin/smart-build-parameter-plugin/")) {
                        return createWebResponse(request, "", "text/plain", 404, "Not Found");
                    }
                    return super.getResponse(request);
                }
            });
            HtmlPage page = openBuildPage(wc, p);

            // Enhancement dead: no marker class, no cards — yet the native form is intact.
            assertEquals(0, page.getByXPath("//form[contains(@class,'sbpd-modern')]").size(),
                    "plugin JS 404 ⇒ no modern marker");
            HtmlElement form = (HtmlElement) page.getFirstByXPath("//form[@name='parameters']");
            assertNotNull(form);
            // Core renders the relative action "build[?delay=…]"; the plugin never rewrites it.
            assertTrue(form.getAttribute("action").contains("build"), "core form action untouched");
            assertEquals("post", form.getAttribute("method"));
            assertNotNull(form.getFirstByXPath(".//input[@name='name' and @value='STR']"));
            assertNotNull(form.getFirstByXPath(".//input[@name='name' and @value='CK']"));

            ((HtmlTextInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='STR']]//input[@type='text']"))
                    .setValue("plain-submit");
            ((HtmlSelect) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='CH']]//select"))
                    .setSelectedAttribute("PROD", true);
            // Multi-select survives on core's buildFormTree alone (json attributes), no plugin JS.
            // Default is {a,b}: unticking a leaves b — a single native-form tick still submits.
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='CK']]//input[@type='checkbox' and @value='a']"))
                    .click();

            HtmlElement build = (HtmlElement) page.getFirstByXPath(
                    "//form[@name='parameters']//button[contains(@class,'jenkins-button--primary')]");
            build.click();

            r.waitUntilNoActivity();
            hudson.model.FreeStyleBuild b = p.getBuildByNumber(1);
            assertNotNull(b, "native submission queued a build");
            String log = b.getLog();
            assertTrue(log.contains("GOT STR=plain-submit"), log);
            assertTrue(log.contains("CH=PROD"), log);
            assertTrue(log.contains("CK=b"), log);
        }
    }
}
