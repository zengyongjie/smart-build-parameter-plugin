package io.github.xvvx.smartbuildparameter;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * FR-4 (docs/REQUIREMENTS.md): options fetched over HTTP(S) — a GET request to a static URL
 * (phase 1), authenticated through the Jenkins credential store (decision D3, never plaintext
 * in the parameter config): {@code basic} → username/password credential sent as
 * {@code Authorization: Basic …}, {@code bearer} → Secret text credential sent as
 * {@code Authorization: Bearer …}.
 *
 * <p>Responses are parsed per {@code responseType}: {@code jsonArray} (top-level JSON array of
 * scalars), {@code jsonPath} (simplified dot path with one {@code [*]} expansion, e.g.
 * {@code data.list[*].name}), or {@code textLines} (one option per line). TLS verification
 * follows the Jenkins JVM defaults; timeouts, non-2xx statuses, over-limit bodies and parse
 * failures surface as inline errors exactly like FR-3. The credential secret never appears in
 * any message or log.</p>
 */
public class HttpSelectSource extends SmartSelectSource {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(HttpSelectSource.class.getName());

    static final int DEFAULT_TIMEOUT_SECONDS = 10;
    static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

    static final String AUTH_NONE = "none";
    static final String AUTH_BASIC = "basic";
    static final String AUTH_BEARER = "bearer";

    static final String PARSE_JSON_ARRAY = "jsonArray";
    static final String PARSE_JSON_PATH = "jsonPath";
    static final String PARSE_TEXT_LINES = "textLines";

    private final String url;
    private final int timeoutSeconds;
    private final int cacheTtlSeconds;
    private final String authType;
    private final String credentialsId;
    private final String responseType;
    private final String jsonPath;
    private final int maxOutputBytes;

    /** One client per fetch: cheap enough, immune to config changes and serialization concerns. */
    private transient HttpClient client;

    @DataBoundConstructor
    public HttpSelectSource(String url, Integer timeoutSeconds, Integer cacheTtlSeconds, String authType,
            String credentialsId, String responseType, String jsonPath, Integer maxOutputBytes) {
        this.url = Util.fixNull(url).trim();
        this.timeoutSeconds = timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        this.cacheTtlSeconds = cacheTtlSeconds == null || cacheTtlSeconds < 0 ? 0 : cacheTtlSeconds;
        this.authType = normalize(authType, AUTH_NONE, AUTH_BASIC, AUTH_BEARER);
        this.credentialsId = Util.fixNull(credentialsId);
        this.responseType = normalize(responseType, PARSE_JSON_ARRAY, PARSE_JSON_PATH, PARSE_TEXT_LINES);
        this.jsonPath = Util.fixNull(jsonPath).trim();
        this.maxOutputBytes = maxOutputBytes == null || maxOutputBytes <= 0 ? DEFAULT_MAX_OUTPUT_BYTES : maxOutputBytes;
    }

    private static String normalize(String value, String fallback, String... allowed) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.equals(fallback)) {
            return fallback;
        }
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unsupported value '" + value + "' (expected " + fallback + "/" + String.join("/", allowed) + ")");
    }

    public String getUrl() {
        return url;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public String getAuthType() {
        return authType;
    }

    public boolean isBasicAuth() {
        return AUTH_BASIC.equals(authType);
    }

    public boolean isBearerAuth() {
        return AUTH_BEARER.equals(authType);
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getResponseType() {
        return responseType;
    }

    public String getJsonPath() {
        return jsonPath;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    @Override
    protected int cacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    @Override
    @NonNull
    protected List<String> doFetch() throws SmartSourceException {
        if (url.isEmpty()) {
            throw new SmartSourceException(Messages.HttpSelectSource_Url_empty());
        }
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SmartSourceException(Messages.HttpSelectSource_Url_invalid(url));
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new SmartSourceException(Messages.HttpSelectSource_Url_invalid(url));
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();
        applyAuthorization(request);

        HttpResponse<InputStream> response;
        try {
            if (client == null) {
                client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
            }
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SmartSourceException(Messages.HttpSelectSource_Timeout(timeoutSeconds));
        } catch (IOException e) {
            // Connect/IO problems: message names the cause, never carries credentials.
            throw new SmartSourceException(Messages.HttpSelectSource_Connection_failed(e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmartSourceException(Messages.HttpSelectSource_Interrupted());
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            drainQuietly(response.body());
            throw new SmartSourceException(Messages.HttpSelectSource_Http_status(status));
        }

        String body = readBounded(response.body());
        if (body == null) { // over the limit
            throw new SmartSourceException(Messages.HttpSelectSource_Output_too_large(maxOutputBytes));
        }
        try {
            return cleanOptions(parse(body));
        } catch (SmartSourceException e) {
            throw e;
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, () -> "smartSelect http parse failed for " + url + ": " + e);
            throw new SmartSourceException(Messages.HttpSelectSource_Parse_failed());
        }
    }

    /** Adds the credential-store Authorization header (D3); never logs or exposes the secret. */
    private void applyAuthorization(HttpRequest.Builder request) throws SmartSourceException {
        if (AUTH_NONE.equals(authType) || credentialsId.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<? extends Credentials> available = CredentialsProvider.lookupCredentialsInItemGroup(
                (Class<? extends Credentials>) (AUTH_BASIC.equals(authType)
                        ? StandardUsernamePasswordCredentials.class
                        : StringCredentials.class),
                Jenkins.get(), ACL.SYSTEM2, List.of());
        Credentials match = CredentialsMatchers.firstOrNull(available, CredentialsMatchers.withId(credentialsId));
        if (match == null) {
            throw new SmartSourceException(Messages.HttpSelectSource_Credential_not_found(credentialsId));
        }
        if (match instanceof StandardUsernamePasswordCredentials basic) {
            String token = basic.getUsername() + ":" + basic.getPassword().getPlainText();
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        } else if (match instanceof StringCredentials bearer) {
            request.header("Authorization", "Bearer " + bearer.getSecret().getPlainText());
        } else {
            throw new SmartSourceException(Messages.HttpSelectSource_Credential_wrong_type(credentialsId));
        }
    }

    /** Reads up to {@code maxOutputBytes}; returns null when the body exceeds it (over-limit error). */
    private String readBounded(InputStream in) {
        try (InputStream body = in) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int total = 0;
            int n;
            while ((n = body.read(buf)) != -1) {
                total += n;
                if (total > maxOutputBytes) {
                    return null;
                }
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static void drainQuietly(InputStream in) {
        try (InputStream ignored = in) {
            // body fully consumed/closed by try-with-resources
        } catch (IOException ignored) {
            // best effort
        }
    }

    private List<String> parse(String body) throws SmartSourceException {
        switch (responseType) {
            case PARSE_TEXT_LINES:
                return textLines(body);
            case PARSE_JSON_PATH:
                return jsonPath(body);
            case PARSE_JSON_ARRAY:
            default:
                return jsonArray(body);
        }
    }

    private static List<String> jsonArray(String body) throws SmartSourceException {
        JSONArray array;
        try {
            array = JSONArray.fromObject(body.trim());
        } catch (RuntimeException e) {
            throw new SmartSourceException(Messages.HttpSelectSource_Parse_failed());
        }
        List<String> out = new ArrayList<>();
        for (Object item : array) {
            if (item instanceof JSONObject || item instanceof JSONArray) {
                throw new SmartSourceException(Messages.HttpSelectSource_Parse_not_scalar());
            }
            out.add(String.valueOf(item));
        }
        return out;
    }

    /**
     * Simplified dot path with a single {@code [*]} expansion, e.g. {@code data.list[*].name}
     * walks obj → array → scalar field of each element. Path miss / wrong shapes are errors.
     */
    private List<String> jsonPath(String body) throws SmartSourceException {
        if (jsonPath.isEmpty()) {
            throw new SmartSourceException(Messages.HttpSelectSource_JsonPath_empty());
        }
        Object root;
        try {
            root = JSONObject.fromObject(body.trim());
        } catch (RuntimeException e) {
            throw new SmartSourceException(Messages.HttpSelectSource_Parse_failed());
        }
        List<Object> contexts = new ArrayList<>();
        contexts.add(root);
        for (String segment : jsonPath.split("\\.")) {
            String key = segment.endsWith("[*]") ? segment.substring(0, segment.length() - 3) : segment;
            if (key.isEmpty()) {
                throw new SmartSourceException(Messages.HttpSelectSource_JsonPath_invalid(jsonPath));
            }
            List<Object> next = new ArrayList<>();
            for (Object ctx : contexts) {
                Object value = ctx instanceof JSONObject obj ? obj.opt(key) : null;
                if (value == null) {
                    continue; // missing in some contexts: keep whatever the others produce
                }
                if (segment.endsWith("[*]")) {
                    if (!(value instanceof JSONArray array)) {
                        throw new SmartSourceException(Messages.HttpSelectSource_JsonPath_not_array(key, jsonPath));
                    }
                    next.addAll(array);
                } else if (value instanceof JSONArray) {
                    // Walking INTO an array without [*] would silently miss; tell the user instead.
                    throw new SmartSourceException(Messages.HttpSelectSource_JsonPath_needs_wildcard(key, jsonPath));
                } else {
                    next.add(value);
                }
            }
            contexts = next;
        }
        List<String> out = new ArrayList<>();
        for (Object ctx : contexts) {
            if (ctx instanceof JSONObject || ctx instanceof JSONArray) {
                throw new SmartSourceException(Messages.HttpSelectSource_Parse_not_scalar());
            }
            out.add(String.valueOf(ctx));
        }
        if (out.isEmpty()) {
            throw new SmartSourceException(Messages.HttpSelectSource_JsonPath_miss(jsonPath));
        }
        return out;
    }

    private static List<String> textLines(String body) {
        List<String> out = new ArrayList<>();
        for (String line : body.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    @Extension
    @Symbol("http")
    public static class DescriptorImpl extends hudson.model.Descriptor<SmartSelectSource> {

        @Override
        public String getDisplayName() {
            return Messages.HttpSelectSource_DisplayName();
        }

        public FormValidation doCheckUrl(@QueryParameter String url) {
            if (Util.fixNull(url).trim().isEmpty()) {
                return FormValidation.error(Messages.HttpSelectSource_Url_empty());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckJsonPath(@QueryParameter String jsonPath, @QueryParameter String responseType) {
            if (!PARSE_JSON_PATH.equals(responseType)) {
                return FormValidation.ok();
            }
            if (Util.fixNull(jsonPath).trim().isEmpty()) {
                return FormValidation.error(Messages.HttpSelectSource_JsonPath_empty());
            }
            return FormValidation.ok();
        }

        /**
         * Credential picker (D3): lists username/password (basic) and Secret text (bearer)
         * credentials; the secret itself never leaves the store. Type-vs-authType is validated
         * at fetch time, so the picker does not depend on client-side field interplay.
         */
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath hudson.model.Item item) {
            ListBoxModel model = new ListBoxModel();
            if (item != null ? !item.hasPermission(hudson.model.Item.CONFIGURE)
                    : !Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model; // no permission → empty list
            }
            model.add(Messages.HttpSelectSource_Credential_none(), "");
            List<IdCredentials> found = new ArrayList<>();
            found.addAll(CredentialsProvider.lookupCredentialsInItem(StandardUsernamePasswordCredentials.class, item, ACL.SYSTEM2, List.of()));
            found.addAll(CredentialsProvider.lookupCredentialsInItem(StringCredentials.class, item, ACL.SYSTEM2, List.of()));
            for (IdCredentials c : found) {
                model.add(CredentialsNameProvider.name(c) + " (" + c.getId() + ")", c.getId());
            }
            return model;
        }
    }
}
