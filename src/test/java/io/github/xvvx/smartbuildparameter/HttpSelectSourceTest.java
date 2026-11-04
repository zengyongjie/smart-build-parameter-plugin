package io.github.xvvx.smartbuildparameter;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterValue;
import hudson.security.ACL;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-4 automated cases, see docs/TEST_PLAN.md (TC-FR4-001 … TC-FR4-007). All HTTP traffic is
 * served by an embedded {@link HttpServer} stub bound to loopback; the authentication cases
 * inject real credentials into the Jenkins store (D3) and let the stub assert the
 * {@code Authorization} header it received.
 */
@WithJenkins
class HttpSelectSourceTest {

    private static final String BASIC_ID = "fr4-basic";
    private static final String BEARER_ID = "fr4-bearer";
    private static final String BEARER_SECRET = "tok-fr4-do-not-leak";
    private static final String BASIC_USER = "user";
    private static final String BASIC_PASS = "pass";
    private static final String BASIC_HEADER = "Basic " + Base64.getEncoder()
            .encodeToString((BASIC_USER + ":" + BASIC_PASS).getBytes(StandardCharsets.UTF_8));
    private static final String BEARER_HEADER = "Bearer " + BEARER_SECRET;

    /** Last Authorization header the stub saw (set by the /auth/* handlers). */
    private static volatile String lastAuthHeader;

    private static HttpServer server;
    private static ExecutorService serverThreads;
    private static String base;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/array", ex -> respond(ex, 200, "[\"a\",\"b\",\"c\"]"));
        server.createContext("/nested", ex -> respond(ex, 200,
                "{\"tag\":\"v\",\"data\":{\"list\":[{\"name\":\"x\"},{\"name\":\"y\"}]}}"));
        server.createContext("/lines", ex -> respond(ex, 200, "a\nb\n"));
        server.createContext("/invalid", ex -> respond(ex, 200, "not-json{"));
        server.createContext("/unauthorized", ex -> respond(ex, 401, "no token for you"));
        server.createContext("/huge", ex -> respond(ex, 200, "x".repeat(1024 * 1024)));
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "[\"late\"]");
        });
        server.createContext("/auth/basic", ex -> guarded(ex, BASIC_HEADER));
        server.createContext("/auth/bearer", ex -> guarded(ex, BEARER_HEADER));
        serverThreads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverThreads);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private static void respond(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 200 with ["granted"] only for the exact expected header — the stub IS the assertion. */
    private static void guarded(HttpExchange ex, String expected) throws java.io.IOException {
        lastAuthHeader = ex.getRequestHeaders().getFirst("Authorization");
        if (expected.equals(lastAuthHeader)) {
            respond(ex, 200, "[\"granted\"]");
        } else {
            respond(ex, 401, "bad credentials");
        }
    }

    private static HttpSelectSource http(String path, String responseType, String jsonPath) {
        return new HttpSelectSource(base + path, null, null, null, null, responseType, jsonPath, null);
    }

    private static SmartSelectParameterDefinition select(String name, String mode, String defaultValue,
            SmartSelectSource source) {
        return new SmartSelectParameterDefinition(name, mode, null, defaultValue, null, source, "");
    }

    private static void addTestCredentials(JenkinsRule r) throws java.io.IOException, hudson.model.Descriptor.FormException {
        CredentialsStore store = CredentialsProvider.lookupStores(r.jenkins).iterator().next();
        try {
            ACL.impersonate2(ACL.SYSTEM2, (Runnable) () -> {
                try {
                    store.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(
                            CredentialsScope.GLOBAL, BASIC_ID, "FR-4 test", BASIC_USER, BASIC_PASS));
                    store.addCredentials(Domain.global(), new StringCredentialsImpl(
                            CredentialsScope.GLOBAL, BEARER_ID, "FR-4 test",
                            hudson.util.Secret.fromString(BEARER_SECRET)));
                } catch (java.io.IOException | hudson.model.Descriptor.FormException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof java.io.IOException io) {
                throw io;
            }
            throw (hudson.model.Descriptor.FormException) e.getCause();
        }
    }

    // ------------------------------------------------------- TC-FR4-001 … 003: the three parse modes

    /** TC-FR4-001: top-level JSON array of scalars → options. */
    @Test
    void jsonArrayResponseParsing() throws Exception {
        assertEquals(List.of("a", "b", "c"), http("/array", null, null).fetch(false));
    }

    /** TC-FR4-002: JSON path with one {@code [*]} expansion walks into nested arrays. */
    @Test
    void jsonPathResponseParsing() throws Exception {
        assertEquals(List.of("x", "y"), http("/nested", "jsonPath", "data.list[*].name").fetch(false));

        // Structured errors: array without [*], [*] on a non-array, path miss, empty path.
        assertTrue(assertThrows(SmartSelectSource.SmartSourceException.class,
                () -> http("/nested", "jsonPath", "data.list.name").fetch(false)).getMessage()
                .contains("list[*]"), "hint at the wildcard");
        assertTrue(assertThrows(SmartSelectSource.SmartSourceException.class,
                () -> http("/nested", "jsonPath", "tag[*]").fetch(false)).getMessage()
                .contains("not an array"));
        assertTrue(assertThrows(SmartSelectSource.SmartSourceException.class,
                () -> http("/nested", "jsonPath", "nope.x").fetch(false)).getMessage()
                .contains("matched nothing"));
        assertTrue(assertThrows(SmartSelectSource.SmartSourceException.class,
                () -> http("/nested", "jsonPath", "").fetch(false)).getMessage()
                .contains("must not be empty"));
    }

    /** TC-FR4-003: one option per non-empty line. */
    @Test
    void textLinesResponseParsing() throws Exception {
        assertEquals(List.of("a", "b"), http("/lines", "textLines", null).fetch(false));
    }

    // ------------------------------------------------------- TC-FR4-004 / 005: credential-store auth

    /** TC-FR4-004: basic auth header comes from the credential store, not the config. */
    @Test
    void basicAuthHeaderSentFromCredentialStore(JenkinsRule r) throws Exception {
        addTestCredentials(r);
        HttpSelectSource src = new HttpSelectSource(
                base + "/auth/basic", null, null, "basic", BASIC_ID, null, null, null);
        assertEquals(List.of("granted"), src.fetch(false), "stub only grants the exact Basic header");

        // Without the credential in the store the fetch fails with a structured error naming the id.
        HttpSelectSource ghost = new HttpSelectSource(
                base + "/auth/basic", null, null, "basic", "ghost-id", null, null, null);
        String msg = assertThrows(SmartSelectSource.SmartSourceException.class, () -> ghost.fetch(false)).getMessage();
        assertTrue(msg.contains("ghost-id"), "error names the credential id: " + msg);
        assertFalse(msg.contains(BASIC_PASS), "never the secret itself");
    }

    /** TC-FR4-005: bearer Secret text is sent as the Authorization header and never leaks anywhere. */
    @Test
    void bearerTokenSentAndNeverLeaked(JenkinsRule r) throws Exception {
        addTestCredentials(r);
        HttpSelectSource src = new HttpSelectSource(
                base + "/auth/bearer", null, null, "bearer", BEARER_ID, null, null, null);
        assertEquals(List.of("granted"), src.fetch(false), "stub only grants the exact Bearer header");
        assertEquals(BEARER_HEADER, lastAuthHeader);

        // Wrong credential TYPE for the auth type → structured error, still no secret.
        HttpSelectSource wrongType = new HttpSelectSource(
                base + "/auth/basic", null, null, "basic", BEARER_ID, null, null, null);
        String msg = assertThrows(SmartSelectSource.SmartSourceException.class, () -> wrongType.fetch(false)).getMessage();
        assertTrue(msg.contains(BEARER_ID), "error names the credential id: " + msg);
        assertFalse(msg.contains(BEARER_SECRET), "never the secret itself");

        // The rendered build page contains the options but not the token.
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(select("TOK", null, "", src)));
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");
            assertNotNull(page.getFirstByXPath("//input[@type='radio' and @value='granted']"));
            assertFalse(page.asXml().contains(BEARER_SECRET), "token must not appear in the page");
        }
    }

    // ------------------------------------------------------- TC-FR4-006: structured error states

    /** TC-FR4-006: 401 / timeout / invalid JSON / over-limit are structured inline errors, not 500s. */
    @Test
    void structuredErrorStates() {
        SmartSelectSource.SmartSourceException e401 = assertThrows(SmartSelectSource.SmartSourceException.class,
                () -> http("/unauthorized", null, null).fetch(false));
        assertTrue(e401.getMessage().contains("401"), "status shown: " + e401.getMessage());

        HttpSelectSource slow = new HttpSelectSource(base + "/slow", 1, null, null, null, null, null, null);
        SmartSelectSource.SmartSourceException eTimeout = assertThrows(SmartSelectSource.SmartSourceException.class,
                () -> slow.fetch(false));
        assertTrue(eTimeout.getMessage().contains("within 1 second"), "timeout shown: " + eTimeout.getMessage());

        SmartSelectSource.SmartSourceException eJson = assertThrows(SmartSelectSource.SmartSourceException.class,
                () -> http("/invalid", "jsonArray", null).fetch(false));
        assertTrue(eJson.getMessage().contains("not valid JSON"), "parse failure shown: " + eJson.getMessage());

        HttpSelectSource huge = new HttpSelectSource(base + "/huge", null, null, null, null, null, null, 1024);
        SmartSelectSource.SmartSourceException eLimit = assertThrows(SmartSelectSource.SmartSourceException.class,
                () -> huge.fetch(false));
        assertTrue(eLimit.getMessage().contains("exceeds"), "over-limit shown: " + eLimit.getMessage());
    }

    /** TC-FR4-006/E02: the build page renders the 401 inline error (no value control) while siblings work. */
    @Test
    void buildPageShowsHttpErrorAndSiblingOptions(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                select("BAD", null, "", http("/unauthorized", null, null)),
                select("OK", "multiple", "", http("/array", null, null))));
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build");

            HtmlElement badBlock = (HtmlElement) page.getFirstByXPath(
                    "//div[@name='parameter'][.//input[@name='name' and @value='BAD']]");
            assertNotNull(badBlock.getFirstByXPath(".//div[@data-smart-select-error='true']"),
                    "401 renders as inline error");
            assertNull(badBlock.getFirstByXPath(".//input[@name='value']"), "no value control for failed source");
            assertTrue(badBlock.getFirstByXPath(".//div[@data-smart-select-error='true']//span[contains(text(),'401')]") != null,
                    "status shown inline");

            assertNotNull(page.getFirstByXPath(
                            "//div[@name='parameter'][.//input[@name='name' and @value='OK']]//input[@type='checkbox' and @value='c']"),
                    "sibling http-sourced parameter renders its options");
        }
    }

    // ------------------------------------------------------- config form roundtrip

    /** Form/binding drift guard (same role as FR-3's configRoundtrip), full field set. */
    @Test
    void configRoundtrip(JenkinsRule r) throws Exception {
        addTestCredentials(r);
        FreeStyleProject p = r.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(new SmartSelectParameterDefinition(
                "BR", "multiple", false, "a", ";",
                new HttpSelectSource(base + "/array", 9, 60, "bearer", BEARER_ID, "jsonPath",
                        "data.list[*].name", 4096),
                "")));
        r.configRoundtrip(p);

        SmartSelectParameterDefinition after = assertInstanceOf(SmartSelectParameterDefinition.class,
                p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("BR"));
        HttpSelectSource source = assertInstanceOf(HttpSelectSource.class, after.getSource());
        assertEquals(base + "/array", source.getUrl());
        assertEquals(9, source.getTimeoutSeconds());
        assertEquals(60, source.getCacheTtlSeconds());
        assertEquals("bearer", source.getAuthType());
        assertEquals(BEARER_ID, source.getCredentialsId());
        assertEquals("jsonPath", source.getResponseType());
        assertEquals("data.list[*].name", source.getJsonPath());
        assertEquals(4096, source.getMaxOutputBytes());
    }

    // ------------------------------------------------------- TC-FR4-007: Pipeline

    /** TC-FR4-007: `smartSelect(source: http(...))` registers from Pipeline and the value reaches the build. */
    @Test
    void pipelineSymbolRegistersHttpSource(JenkinsRule r) throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "pipe-http");
        p.setDefinition(new CpsFlowDefinition(
                "properties([parameters([smartSelect(name: 'BR', defaultValue: 'a', source: "
                        + "http(url: '" + base + "/lines', responseType: 'textLines'))])])\n"
                        + "node { echo \"branch=${params.BR}\" }", true));
        r.buildAndAssertSuccess(p);

        SmartSelectParameterDefinition d = assertInstanceOf(SmartSelectParameterDefinition.class,
                p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("BR"));
        HttpSelectSource source = assertInstanceOf(HttpSelectSource.class, d.getSource());
        assertEquals(base + "/lines", source.getUrl());
        assertEquals("textLines", source.getResponseType());
        assertTrue(r.buildAndAssertSuccess(p).getLog().contains("branch=a"));
        assertEquals("a", ((StringParameterValue) d.getDefaultParameterValue()).getValue());
    }
}
