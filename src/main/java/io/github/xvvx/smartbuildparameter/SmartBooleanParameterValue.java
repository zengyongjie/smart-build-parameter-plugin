package io.github.xvvx.smartbuildparameter;

import hudson.model.BooleanParameterValue;

/**
 * FR-6 (docs/REQUIREMENTS.md): build-history value of {@code smartBoolean}. Semantics are
 * core's {@link BooleanParameterValue} exactly ({@code params.NAME == true/false} in
 * pipelines); the subclass exists only so the build Parameters page resolves this plugin's
 * {@code value.jelly} (true/false badge) while native boolean parameters keep core's
 * rendering (view resolution is by runtime type).
 */
public class SmartBooleanParameterValue extends BooleanParameterValue {

    private static final long serialVersionUID = 1L;

    public SmartBooleanParameterValue(String name, boolean value, String description) {
        super(name, value, description);
    }
}
