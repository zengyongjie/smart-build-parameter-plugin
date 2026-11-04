package io.github.xvvx.smartbuildparameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * FR-3 (docs/REQUIREMENTS.md): dynamic dropdown parameter whose options come from a
 * {@link SmartSelectSource} (shell now, HTTP in FR-4). The command/request runs when the
 * build form renders (or is served from the TTL cache); failures render as an inline error
 * with a retry entry instead of a broken page (FR-3 行为规范 4).
 *
 * <p>Value semantics match FR-2 (decision D2): the submitted value is one string — the
 * selection joined with {@code delimiter} in list order for multi-select.</p>
 */
public class SmartSelectParameterDefinition extends SimpleParameterDefinition {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(SmartSelectParameterDefinition.class.getName());

    static final String MODE_SINGLE = "single";
    static final String MODE_MULTIPLE = "multiple";
    static final String DEFAULT_DELIMITER = ",";

    private final String selectMode;
    /** Boolean object so Pipeline can omit it and still get the spec default {@code true}. */
    private final Boolean filterable;
    private final String defaultValue;
    private final String delimiter;
    private final SmartSelectSource source;

    @DataBoundConstructor
    public SmartSelectParameterDefinition(String name, String selectMode, Boolean filterable,
            String defaultValue, String delimiter, SmartSelectSource source, String description) {
        super(name, description);
        if (selectMode == null || selectMode.isEmpty() || MODE_SINGLE.equals(selectMode)) {
            this.selectMode = MODE_SINGLE;
        } else if (MODE_MULTIPLE.equals(selectMode)) {
            this.selectMode = MODE_MULTIPLE;
        } else {
            throw new IllegalArgumentException("selectMode must be '" + MODE_SINGLE + "' or '" + MODE_MULTIPLE + "': " + selectMode);
        }
        this.filterable = filterable;
        this.defaultValue = Util.fixNull(defaultValue);
        this.delimiter = delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
        this.source = source;
    }

    public String getSelectMode() {
        return selectMode;
    }

    public Boolean getFilterable() {
        return filterable;
    }

    /** Spec default is {@code true}: only an explicit {@code false} disables filtering. */
    public boolean isFilterable() {
        return !Boolean.FALSE.equals(filterable);
    }

    /**
     * View-facing alias for {@link #isFilterable()}: in Jelly/JEXL {@code it.filterable} binds
     * to {@link #getFilterable()} — a {@code Boolean} that is null when Pipeline omitted the
     * field — so the view must use this name instead.
     */
    public boolean isFilterableEnabled() {
        return isFilterable();
    }

    public boolean isMultiple() {
        return MODE_MULTIPLE.equals(selectMode);
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public SmartSelectSource getSource() {
        return source;
    }

    // ---------------------------------------------------------------- option evaluation

    /** Render-time evaluation result: exactly one of the fields is meaningful. */
    public static final class OptionsResult {
        public final List<String> options;
        public final String error;

        private OptionsResult(List<String> options, String error) {
            this.options = options;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * The build page calls this ONCE per render (the view stores it in a Jelly variable).
     * With {@code cacheTtlSeconds > 0} repeated renders are served from the source cache.
     */
    @NonNull
    public OptionsResult getOptionsOrError() {
        if (source == null) {
            return new OptionsResult(List.of(), Messages.SmartSelectParameterDefinition_Source_missing());
        }
        try {
            return new OptionsResult(source.fetch(false), null);
        } catch (SmartSelectSource.SmartSourceException e) {
            LOGGER.log(Level.FINE, () -> "smartSelect '" + getName() + "' source failed: " + e.getMessage());
            return new OptionsResult(List.of(), e.getMessage());
        }
    }

    /** Pre-selection test used by the view (against THIS render's option list). */
    public boolean isDefaultSelected(String option, List<String> options) {
        if (defaultValue.trim().isEmpty() || !options.contains(option)) {
            return false;
        }
        for (String value : defaultValue.split(java.util.regex.Pattern.quote(delimiter))) {
            if (option.equals(value.trim())) {
                return true;
            }
        }
        return false;
    }

    /** What the closed dropdown box shows before the user picks anything. */
    public String getDisplayValue() {
        return join(getDefaultSelectionList());
    }

    /** Default selection as a list: single mode → first default item; multiple → dedup'd, order kept. */
    @NonNull
    List<String> getDefaultSelectionList() {
        if (defaultValue.trim().isEmpty()) {
            return List.of();
        }
        if (!isMultiple()) {
            String first = defaultValue.split(java.util.regex.Pattern.quote(delimiter))[0].trim();
            return first.isEmpty() ? List.of() : List.of(first);
        }
        List<String> keep = new ArrayList<>();
        for (String value : defaultValue.split(java.util.regex.Pattern.quote(delimiter))) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !keep.contains(trimmed)) {
                keep.add(trimmed);
            }
        }
        return keep;
    }

    // ---------------------------------------------------------------- value semantics

    @NonNull
    String join(List<String> values) {
        return String.join(delimiter, values);
    }

    @Override
    public ParameterValue createValue(String value) {
        // CLI / programmatic path receives an already-joined value (e.g. `build -p NAME=a,b`);
        // re-split on the delimiter so the history page can chip it (FR-6). Unambiguous
        // because options containing the delimiter are rejected at config time (增补 A).
        return SmartMultiParameterValue.ofJoined(getName(), value, delimiter, getDescription());
    }

    @Override
    public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
        // Structured form submission: repeated `value` inputs arrive as a JSONArray
        // (selection order = list order, like FR-2), a single selection as a plain string.
        List<String> selected = new ArrayList<>();
        Object value = jo.get("value");
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                String option = String.valueOf(item);
                if (!selected.contains(option)) {
                    selected.add(option);
                }
            }
        } else if (value != null && !String.valueOf(value).isEmpty()) {
            // A plain string is either a single selection or a pre-joined value (remote API /
            // API-driven `json` submissions) — re-split on the delimiter so chips match the
            // selection (same unambiguity argument as createValue(String), 增补 A).
            return SmartMultiParameterValue.ofJoined(getName(), String.valueOf(value), delimiter, getDescription());
        }
        return new SmartMultiParameterValue(getName(), join(selected), selected, getDescription());
    }

    @Override
    public StringParameterValue getDefaultParameterValue() {
        List<String> selected = getDefaultSelectionList();
        return new SmartMultiParameterValue(getName(), join(selected), selected, getDescription());
    }

    @Override
    public ParameterDefinition copyWithDefaultValue(ParameterValue defaultValue) {
        if (defaultValue instanceof StringParameterValue value) {
            return new SmartSelectParameterDefinition(getName(), selectMode, filterable,
                    value.getValue(), delimiter, source, getDescription());
        }
        return this;
    }

    @Extension
    @Symbol("smartSelect")
    public static class DescriptorImpl extends ParameterDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.SmartSelectParameterDefinition_DisplayName();
        }

    }
}
