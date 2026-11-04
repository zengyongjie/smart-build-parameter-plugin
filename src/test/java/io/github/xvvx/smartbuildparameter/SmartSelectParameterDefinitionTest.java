package io.github.xvvx.smartbuildparameter;

import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterValue;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlTextInput;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-3 automated cases, see docs/TEST_PLAN.md (TC-FR3-001 … TC-FR3-008).
 */
@WithJenkins
class SmartSelectParameterDefinitionTest {

    @TempDir
    File tmp;

    private static String fetchJson(JenkinsRule.WebClient wc, String url) throws Exception {
        org.htmlunit.UnexpectedPage page = (org.htmlunit.UnexpectedPage) wc.goTo(url, "application/json");
        return new String(page.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ShellSelectSource shell(String command) {
        return new ShellSelectSource(command, null, null, null);
    }

    private static ShellSelectSource shell(String command, Integer timeout, Integer ttl, Integer max) {
        return new ShellSelectSource(command, timeout, ttl, max);
    }

    private static SmartSelectParameterDefinition select(String name, String mode, Boolean filterable,
            String defaultValue, String delimiter, ShellSelectSource source) {
        return new SmartSelectParameterDefinition(name, mode, filterable, defaultValue, delimiter, source, "");
    }

    // ---------------------------------------------------------------- TC-FR3-001 … 005: source behavior

    /** TC-FR3-001: one option per output line, blank lines dropped, whitespace trimmed, deduplicated. */
    @Test
    void shellOptionsParsing() throws Exception {
        assertEquals(List.of("a", "b"), shell("printf 'a\\n\\n b \\n\\nb\\n'").fetch(false));
        assertEquals(List.of("x", "y"), shell("printf 'x\\r\\ny\\r\\n'").fetch(false));
        assertTrue(shell("true").fetch(false).isEmpty());
    }

    /** TC-FR3-002: non-zero exit is a structured render error (no exception, no 500), stderr kept. */
    @Test
    void shellFailureIsStructuredError() {
        SmartSelectParameterDefinition.OptionsResult r =
                select("BR", null, null, "", null, shell("echo boom 1>&2; exit 3")).getOptionsOrError();
        assertNotNull(r.error);
        assertTrue(r.error.contains("3"), "exit code in message: " + r.error);
        assertTrue(r.error.contains("boom"), "stderr detail in message: " + r.error);
        assertTrue(r.options.isEmpty());
        assertFalse(r.isSuccess());
    }

    /** TC-FR3-003: `sleep 30` with a 2s timeout aborts quickly with a timeout error. */
    @Test
    void shellTimeoutKillsProcess() {
        SmartSelectParameterDefinition.OptionsResult r =
                select("BR", null, null, "", null, shell("sleep 30", 2, 0, null)).getOptionsOrError();
        assertNotNull(r.error);
        assertTrue(r.error.contains("2"), "timeout seconds in message: " + r.error);
    }

    /** TC-FR3-004: output beyond maxOutputBytes is an over-limit error, not silent truncation. */
    @Test
    void shellOutputOverLimit() {
        SmartSelectParameterDefinition.OptionsResult r =
                select("BR", null, null, "", null, shell("yes | head -c 2048", null, null, 1024)).getOptionsOrError();
        assertNotNull(r.error);
        assertTrue(r.error.contains("exceeds"), "limit in message: " + r.error);
    }

    /** TC-FR3-005: TTL cache executes once per window and again after expiry (marker file counts runs). */
    @Test
    void shellCacheTtlCachesAndExpires() throws Exception {
        File marker = new File(tmp, "marker");
        String command = "echo x >> " + marker.getPath() + "; printf ok";

        ShellSelectSource cached = shell(command, null, 60, null);
        assertEquals(List.of("ok"), cached.fetch(false));
        assertEquals(List.of("ok"), cached.fetch(false));
        assertEquals(1, Files.readAllLines(marker.toPath()).size(), "TTL window: command ran once");

        ShellSelectSource shortTtl = shell(command, null, 1, null);
        shortTtl.fetch(false);
        Thread.sleep(1200);
        shortTtl.fetch(false);
        assertEquals(3, Files.readAllLines(marker.toPath()).size(), "after expiry the command runs again (cached=1 + shortTtl=2)");
    }

    /** TC-FR3-005 (thread safety): concurrent fills execute the command exactly once. */
    @Test
    void shellCacheConcurrentSingleExecution() throws Exception {
        File marker = new File(tmp, "concurrent");
        ShellSelectSource source = shell("echo x >> " + marker.getPath() + "; printf ok", null, 60, null);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<String>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<List<String>>) () -> {
                    start.await();
                    return source.fetch(false);
                }));
            }
            start.countDown();
            for (Future<List<String>> f : futures) {
                assertEquals(List.of("ok"), f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, Files.readAllLines(marker.toPath()).size(), "one execution under contention");
    }

    // ---------------------------------------------------------------- value semantics

    /** TC-FR3-006 (D2): multiple submits join in list order; single passes the string through. */
    @Test
    void multipleSubmissionJoinsInOrder() {
        SmartSelectParameterDefinition d = select("SVCS", "multiple", null, "", ",", shell("printf 'a\\nb'"));

        JSONObject array = new JSONObject();
        array.put("value", JSONArray.fromObject(List.of("b", "a")));
        assertEquals("b,a", ((StringParameterValue) d.createValue((org.kohsuke.stapler.StaplerRequest2) null, array)).getValue());

        JSONObject single = new JSONObject();
        single.put("value", "b");
        assertEquals("b", ((StringParameterValue) d.createValue((org.kohsuke.stapler.StaplerRequest2) null, single)).getValue());

        assertEquals("", ((StringParameterValue) d.createValue((org.kohsuke.stapler.StaplerRequest2) null, new JSONObject())).getValue());
        assertEquals("x,y", ((StringParameterValue) d.createValue("x,y")).getValue());
    }

    /** Spec defaults: single mode, filtering on, "," delimiter; explicit false disables filtering. */
    @Test
    void specDefaults() {
        SmartSelectParameterDefinition d = select("BR", null, null, null, null, shell("true"));
        assertEquals("single", d.getSelectMode());
        assertTrue(d.isFilterable());
        assertEquals(",", d.getDelimiter());
        assertFalse(select("BR", "multiple", false, null, null, shell("true")).isFilterable());
    }

    // ---------------------------------------------------------------- config roundtrip

    /** Config form / binding drift guard: every field survives a config roundtrip. */
    @Test
    void configRoundtrip(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(select("BR", "multiple", false, "a,b", ";",
                shell("printf 'a\\nb'", 9, 60, 123))));
        r.configRoundtrip(p);

        SmartSelectParameterDefinition after = assertInstanceOf(SmartSelectParameterDefinition.class,
                p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("BR"));
        assertEquals("multiple", after.getSelectMode());
        assertFalse(after.isFilterable());
        assertEquals("a,b", after.getDefaultValue());
        assertEquals(";", after.getDelimiter());
        ShellSelectSource source = assertInstanceOf(ShellSelectSource.class, after.getSource());
        assertEquals("printf 'a\\nb'", source.getCommand());
        assertEquals(9, source.getTimeoutSeconds());
        assertEquals(60, source.getCacheTtlSeconds());
        assertEquals(123, source.getMaxOutputBytes());
    }

    // ---------------------------------------------------------------- build page (TC-FR3-007)

    /** TC-FR3-007: widget DOM (filter + list; radio vs checkbox semantics) and live filtering. */
    @Test
    void buildPageRendersWidgetAndFilter(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                select("BR", "single", null, "dev", null, shell("printf 'dev\\nprod\\nstage'")),
                select("SVCS", "multiple", null, "", null, shell("printf 'a\\nb'"))));
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            // The build-parameter form renders normally but answers 405 on purpose.
            wc.setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");

            assertNotNull(page.getFirstByXPath(
                    "//div[@data-smart-param='select' and @data-mode='single']//input[@type='radio' and @name='value' and @value='dev']"));
            assertNotNull(page.getFirstByXPath(
                    "//div[@data-smart-param='select' and @data-mode='multiple']//input[@type='checkbox' and @name='value' and @json='b']"));
            assertEquals(2, page.getByXPath("//input[contains(@class,'smart-select-filter')]").size(),
                    "filterable defaults to true for both widgets");
            assertTrue(page.getElementsByTagName("script").stream()
                    .anyMatch(e -> e.getAttribute("src").contains("smart-select.js")));

            // Open the single-mode dropdown.
            HtmlElement box = (HtmlElement) page.getFirstByXPath(
                    "//div[@data-mode='single']//button[contains(@class,'smart-select-box')]");
            box.click();
            HtmlElement list = (HtmlElement) page.getFirstByXPath(
                    "//div[@data-mode='single']//div[contains(@class,'smart-select-list')]");
            assertFalse(list.getAttribute("style").contains("none"), "list opens on box click");

            // D4: typing filters the option list client-side.
            HtmlTextInput filter = (HtmlTextInput) page.getFirstByXPath(
                    "//div[@data-mode='single']//input[contains(@class,'smart-select-filter')]");
            filter.type("de");
            HtmlElement prod = (HtmlElement) page.getFirstByXPath(
                    "//div[@data-mode='single']//label[contains(@class,'smart-select-option')][span='prod']");
            HtmlElement dev = (HtmlElement) page.getFirstByXPath(
                    "//div[@data-mode='single']//label[contains(@class,'smart-select-option')][span='dev']");
            assertTrue(prod.getAttribute("style").contains("none"), "non-matching option hidden");
            assertFalse(dev.getAttribute("style").contains("none"), "matching option visible");

            // Radio semantics: picking collapses the list and echoes into the box.
            HtmlRadioButtonInput devRadio = (HtmlRadioButtonInput) page.getFirstByXPath(
                    "//div[@data-mode='single']//input[@type='radio' and @value='dev']");
            devRadio.click();
            HtmlElement value = (HtmlElement) page.getFirstByXPath(
                    "//div[@data-mode='single']//span[contains(@class,'smart-select-value')]");
            assertEquals("dev", value.getTextContent().trim());
            assertTrue(list.getAttribute("style").contains("none"), "list collapses after radio pick");

            // Multiple mode is a conventional single-line multi-select: NO confirm button,
            // selections apply live, and the trigger echoes the joined value (D2, option order).
            assertEquals(0, page.getByXPath("//button[contains(@class,'smart-select-confirm')]").size(),
                    "no confirm button — selections apply live");
            HtmlElement multiBox = (HtmlElement) page.getFirstByXPath(
                    "//div[@data-mode='multiple']//button[contains(@class,'smart-select-box')]");
            multiBox.click();
            HtmlElement multiList = (HtmlElement) page.getFirstByXPath(
                    "//div[@data-mode='multiple']//div[contains(@class,'smart-select-list')]");
            assertFalse(multiList.getAttribute("style").contains("none"), "multiple list opens");
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@data-mode='multiple']//input[@type='checkbox' and @value='b']")).click();
            ((HtmlCheckBoxInput) page.getFirstByXPath(
                    "//div[@data-mode='multiple']//input[@type='checkbox' and @value='a']")).click();
            HtmlElement multiValue = (HtmlElement) page.getFirstByXPath(
                    "//div[@data-mode='multiple']//span[contains(@class,'smart-select-value')]");
            assertEquals("a,b", multiValue.getTextContent().trim(),
                    "trigger shows the live joined value in option order (matches submission)");
            assertFalse(multiList.getAttribute("style").contains("none"),
                    "multiple list stays open while picking");
        }
    }

    /** FR-3 行为规范 4: a failing source renders an inline error with retry and NO value control. */
    @Test
    void buildPageShowsInlineErrorForFailingSource(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                select("BAD", null, null, "", null, shell("exit 7")),
                select("GOOD", null, null, "", null, shell("printf 'ok'"))));
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");

            HtmlElement badBlock = (HtmlElement) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='BAD']]");
            assertNotNull(badBlock.getFirstByXPath(".//div[@data-smart-select-error='true']"));
            assertTrue(badBlock.getFirstByXPath(".//div[@data-smart-select-error='true']//button[contains(@class,'smart-select-retry')]") != null,
                    "retry entry present");
            assertNull(badBlock.getFirstByXPath(".//input[@name='value']"),
                    "failed parameter renders no value control (cannot be submitted)");
            assertTrue(badBlock.getFirstByXPath(".//div[@data-smart-select-error='true']//span[contains(text(),'7')]") != null,
                    "exit code shown inline");

            assertNotNull(page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='GOOD']]//input[@type='radio' and @value='ok']"),
                    "other parameters keep working (E02)");
        }
    }

    /** FR-3 行为规范 4 "retry": the descriptor endpoint force-refreshes and returns options/error JSON. */
    @Test
    void reloadOptionsEndpoint(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        File marker = new File(tmp, "retry");
        p.addProperty(new ParametersDefinitionProperty(
                select("BR", null, null, "", null, shell("[ -f " + marker.getPath() + " ] && printf ok || exit 1"))));
        String url = "smart-build-parameter/reloadOptions?job=" + p.getName() + "&name=BR";
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            String first = fetchJson(wc, url);
            assertTrue(first.contains("error"), "first attempt fails: " + first);

            marker.createNewFile();
            String second = fetchJson(wc, url);
            assertTrue(second.contains("ok"), "retry succeeds after the source recovers: " + second);
        }
    }

    // ---------------------------------------------------------------- pipeline (TC-FR3-008)

    /** TC-FR3-008: `smartSelect` registers from Pipeline properties() and the value reaches the build. */
    @Test
    void pipelineSymbolRegistersParameter(JenkinsRule r) throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "pipe-select");
        p.setDefinition(new CpsFlowDefinition(
                "properties([parameters([smartSelect(name: 'BR', defaultValue: 'main', source: shell(command: 'printf \"main\\\\ndev\"'))])])\n"
                        + "node { echo \"branch=${params.BR}\" }", true));
        r.buildAndAssertSuccess(p);

        SmartSelectParameterDefinition d = assertInstanceOf(SmartSelectParameterDefinition.class,
                p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("BR"));
        assertEquals("single", d.getSelectMode());
        assertEquals("main", ((StringParameterValue) d.getDefaultParameterValue()).getValue());
        assertTrue(r.buildAndAssertSuccess(p).getLog().contains("branch=main"));
    }
}
