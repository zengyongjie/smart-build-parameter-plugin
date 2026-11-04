package io.github.xvvx.smartbuildparameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.ParameterValue;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * FR-2 (see docs/REQUIREMENTS.md): multi-select checkbox parameter.
 *
 * <p>Value semantics (decision D2): the submitted value is a single string made of the
 * selected options joined in user-selection order with the configured delimiter
 * (default {@code ,}), so declarative pipelines consume it as {@code params.NAME}.</p>
 */
public class SmartCheckboxParameterDefinition extends SimpleParameterDefinition {

    private static final long serialVersionUID = 1L;

    static final String DEFAULT_DELIMITER = ",";

    /** Newline-separated option lines. */
    private final String options;
    /** Delimiter-separated default selections. */
    private final String defaultChecked;
    private final String delimiter;
    private final int minSelected;

    @DataBoundConstructor
    public SmartCheckboxParameterDefinition(String name, String options, String defaultChecked, String delimiter, int minSelected, String description) {
        super(name, description);
        this.options = Util.fixNull(options);
        this.defaultChecked = Util.fixNull(defaultChecked);
        this.delimiter = delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
        this.minSelected = minSelected;
    }

    public String getOptions() {
        return options;
    }

    public String getDefaultChecked() {
        return defaultChecked;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public int getMinSelected() {
        return minSelected;
    }

    /** Parsed option list: blank lines removed, lines trimmed, order preserved. */
    @NonNull
    public List<String> getOptionList() {
        List<String> out = new ArrayList<>();
        for (String line : options.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Parsed default selections: kept only when they match a configured option, order preserved, deduplicated. */
    @NonNull
    List<String> getDefaultCheckedList() {
        List<String> all = getOptionList();
        List<String> out = new ArrayList<>();
        for (String value : defaultChecked.split(Pattern.quote(delimiter))) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && all.contains(trimmed) && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Used by the build-page view to mark pre-checked boxes. */
    public boolean isDefaultChecked(String option) {
        return getDefaultCheckedList().contains(option);
    }

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
        // (selection order preserved), a single selection as a plain string.
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
        List<String> defaults = getDefaultCheckedList();
        return new SmartMultiParameterValue(getName(), join(defaults), defaults, getDescription());
    }

    @Extension
    @Symbol("smartCheckbox")
    public static class DescriptorImpl extends ParameterDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.SmartCheckboxParameterDefinition_DisplayName();
        }

        /** Requirement FR-2 item 5 (增补 A): option values must not contain the delimiter, must not repeat. */
        public FormValidation doCheckOptions(@QueryParameter String options, @QueryParameter String delimiter) {
            String sep = delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
            List<String> seen = new ArrayList<>();
            int count = 0;
            for (String line : Util.fixNull(options).split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                count++;
                if (trimmed.contains(sep)) {
                    return FormValidation.error(Messages.SmartCheckboxParameterDefinition_Options_delimiter_conflict(trimmed, sep));
                }
                if (seen.contains(trimmed)) {
                    return FormValidation.error(Messages.SmartCheckboxParameterDefinition_Options_duplicate(trimmed));
                }
                seen.add(trimmed);
            }
            if (count == 0) {
                return FormValidation.error(Messages.SmartCheckboxParameterDefinition_Options_empty());
            }
            return FormValidation.ok();
        }

        /** Requirement FR-2 item 5 (增补 A): defaults must reference configured options. */
        public FormValidation doCheckDefaultChecked(@QueryParameter String defaultChecked, @QueryParameter String options, @QueryParameter String delimiter) {
            String sep = delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
            List<String> all = parseOptions(Util.fixNull(options));
            if (Util.fixNull(defaultChecked).trim().isEmpty()) {
                return FormValidation.ok();
            }
            List<String> unknown = new ArrayList<>();
            for (String value : defaultChecked.split(Pattern.quote(sep))) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty() && !all.contains(trimmed) && !unknown.contains(trimmed)) {
                    unknown.add(trimmed);
                }
            }
            if (!unknown.isEmpty()) {
                return FormValidation.error(Messages.SmartCheckboxParameterDefinition_DefaultChecked_unknown(String.join(sep, unknown)));
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckMinSelected(@QueryParameter String minSelected, @QueryParameter String options) {
            int min;
            try {
                min = Integer.parseInt(Util.fixNull(minSelected).trim());
            } catch (NumberFormatException e) {
                return FormValidation.error(Messages.SmartCheckboxParameterDefinition_MinSelected_not_a_number());
            }
            if (min < 0) {
                return FormValidation.error(Messages.SmartCheckboxParameterDefinition_MinSelected_negative());
            }
            int optionCount = parseOptions(Util.fixNull(options)).size();
            if (min > 0 && optionCount > 0 && min > optionCount) {
                return FormValidation.error(Messages.SmartCheckboxParameterDefinition_MinSelected_too_large(optionCount));
            }
            return FormValidation.ok();
        }

        private static List<String> parseOptions(String options) {
            List<String> out = new ArrayList<>();
            for (String line : options.split("\r?\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
            return out;
        }
    }
}
