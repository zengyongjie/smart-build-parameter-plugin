package io.github.xvvx.smartbuildparameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.BooleanParameterValue;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.SimpleParameterDefinition;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.List;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * FR-1 (see docs/REQUIREMENTS.md): boolean parameter that controls the visibility of other
 * parameters on the Build With Parameters page.
 *
 * <p>Visibility itself is a front-end concern handled by {@code js/smart-params.js}: a hidden
 * parameter block gets core's {@code field-disabled} attribute, so structured-form submission
 * ({@code buildFormTree}) excludes it entirely — decision D1: hidden parameters are NOT submitted
 * (absent from {@code ParametersAction} and {@code params}), and hidden blocks are exempt from
 * client-side validation such as {@code smartCheckbox} {@code minSelected} (增补 B).</p>
 *
 * <p>Value semantics mirror the native boolean parameter: {@code BooleanParameterValue},
 * i.e. {@code params.NAME == true/false} in pipelines.</p>
 */
public class SmartBooleanParameterDefinition extends SimpleParameterDefinition {

    private static final long serialVersionUID = 1L;

    private final boolean defaultValue;
    /** Comma-separated names of parameters (any type) whose visibility this parameter controls. */
    private final String controls;

    @DataBoundConstructor
    public SmartBooleanParameterDefinition(String name, boolean defaultValue, String controls, String description) {
        super(name, description);
        this.defaultValue = defaultValue;
        this.controls = Util.fixNull(controls);
    }

    /** Mirrors core {@code BooleanParameterDefinition#isDefaultValue()} naming. */
    public boolean isDefaultValue() {
        return defaultValue;
    }

    public String getControls() {
        return controls;
    }

    /** Parsed control list: comma-separated, trimmed, blanks skipped, deduplicated, order kept. */
    @NonNull
    public List<String> getControlList() {
        List<String> out = new ArrayList<>();
        for (String token : controls.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    @Override
    public ParameterDefinition copyWithDefaultValue(ParameterValue defaultValue) {
        if (defaultValue instanceof BooleanParameterValue value) {
            Boolean v = value.getValue(); // @CheckForNull on this baseline's core signature
            return new SmartBooleanParameterDefinition(getName(), v != null && v, getControls(), getDescription());
        }
        return this;
    }

    @Override
    public ParameterValue createValue(String value) {
        // CLI / remote-API path: `build -p NAME=true` (anything not parseable as true is false,
        // same leniency as the native boolean parameter).
        return new SmartBooleanParameterValue(getName(), Boolean.parseBoolean(value), getDescription());
    }

    @Override
    public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
        // Structured form submission binds checkboxes to booleans (buildFormTree), so `value`
        // is a JSON boolean. Unlike core's BooleanParameterDefinition we avoid bindJSON so the
        // method also works with a null request (programmatic calls).
        return new SmartBooleanParameterValue(getName(), jo.optBoolean("value"), getDescription());
    }

    @Override
    public BooleanParameterValue getDefaultParameterValue() {
        return new SmartBooleanParameterValue(getName(), defaultValue, getDescription());
    }

    @Extension
    @Symbol("smartBoolean")
    public static class DescriptorImpl extends ParameterDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.SmartBooleanParameterDefinition_DisplayName();
        }

        /**
         * Requirement FR-1: unknown names in {@code controls} are a configuration-time hint
         * (warning, not error — saving stays possible; the build form simply ignores missing names).
         */
        public FormValidation doCheckControls(@QueryParameter String controls, @AncestorInPath Job<?, ?> job) {
            List<String> names = new SmartBooleanParameterDefinition("x", false, controls, null).getControlList();
            if (names.isEmpty()) {
                return FormValidation.ok();
            }
            List<String> duplicated = new ArrayList<>();
            List<String> tokens = new ArrayList<>();
            for (String token : Util.fixNull(controls).split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    if (tokens.contains(trimmed) && !duplicated.contains(trimmed)) {
                        duplicated.add(trimmed);
                    }
                    tokens.add(trimmed);
                }
            }
            if (!duplicated.isEmpty()) {
                return FormValidation.warning(Messages.SmartBooleanParameterDefinition_Controls_duplicate(String.join(", ", duplicated)));
            }
            if (job != null) {
                ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
                List<String> unknown = new ArrayList<>();
                for (String name : names) {
                    if ((property == null || property.getParameterDefinition(name) == null) && !unknown.contains(name)) {
                        unknown.add(name);
                    }
                }
                if (!unknown.isEmpty()) {
                    return FormValidation.warning(Messages.SmartBooleanParameterDefinition_Controls_unknown(String.join(", ", unknown)));
                }
            }
            return FormValidation.ok();
        }
    }
}
