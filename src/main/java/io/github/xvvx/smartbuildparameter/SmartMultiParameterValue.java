package io.github.xvvx.smartbuildparameter;

import hudson.model.StringParameterValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * FR-6 (docs/REQUIREMENTS.md): the submitted value of a multi-select parameter
 * ({@code smartCheckbox}, multiple-mode {@code smartSelect}) — the joined string
 * (identical API/env semantics to core's {@link StringParameterValue}) plus the ordered
 * selection it was built from, so the build-history Parameters page can render one chip per
 * selected value next to the raw joined string.
 *
 * <p>The component values are persisted with the build (ParametersAction), so replays and
 * re-runs render exactly what was submitted. {@code createValue(String)} paths (CLI /
 * remote API) receive a pre-joined string and re-split it on the delimiter — unambiguous
 * because options containing the delimiter are rejected at config time (增补 A). The field
 * is a plain {@code ArrayList} for XStream compatibility with persisted builds.</p>
 */
public class SmartMultiParameterValue extends StringParameterValue {

    private static final long serialVersionUID = 1L;

    /** Selected values in submission order; rendered as chips by value.jelly. */
    private final List<String> choices;

    public SmartMultiParameterValue(String name, String joinedValue, List<String> choices, String description) {
        super(name, joinedValue == null ? "" : joinedValue, description);
        this.choices = new ArrayList<>(choices == null ? List.of() : choices);
    }

    /** Re-splits a pre-joined value on the delimiter (CLI / remote API path). */
    public static SmartMultiParameterValue ofJoined(String name, String joined, String delimiter, String description) {
        String value = joined == null ? "" : joined;
        List<String> parts = value.isEmpty()
                ? List.of()
                : Arrays.asList(value.split(Pattern.quote(delimiter == null || delimiter.isEmpty() ? "," : delimiter)));
        return new SmartMultiParameterValue(name, value, parts, description);
    }

    /**
     * Selected values in submission order; used by {@code value.jelly} to render chips.
     * Deliberately NOT {@code @Exported} — the remote API stays exactly core's
     * {@code StringParameterValue} shape ({@code name}/{@code value}), keeping build
     * {@code api/json} consumption conventions intact (FR-6 acceptance 4).
     */
    public List<String> getChoices() {
        return choices;
    }

    /** Core's StringParameterValue equality (name/value) extended by the selection list. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        return o instanceof SmartMultiParameterValue other && choices.equals(other.choices);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + choices.hashCode();
    }
}
