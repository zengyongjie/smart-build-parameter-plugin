package io.github.xvvx.smartbuildparameter;

import hudson.Extension;
import hudson.model.PageDecorator;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * FR-5 (docs/REQUIREMENTS.md): modernizes the Build With Parameters page — card-style
 * parameter grouping, unified control styling, clear primary button — strictly as
 * progressive enhancement.
 *
 * <p>Constraints (FR-5 行为规范 3/4): no core template or resource is overridden — the
 * decorator's {@code header.jelly} only appends this plugin's own scoped CSS/JS, and only for
 * requests whose URI ends in {@code /build} (the Build With Parameters form), so every other
 * Jenkins page stays untouched. The stylesheet itself is dead unless JavaScript adds the
 * {@code sbpd-modern} marker class to the form, so a broken/absent JS falls back to the fully
 * functional native form, and one system property disables the whole feature:</p>
 *
 * <ul>
 *   <li>{@code -Dio.github.xvvx.smartbuildparameter.modernBuildPage=false} — inject nothing
 *       (read per render, toggleable at runtime via the script console).</li>
 * </ul>
 */
@Extension
public class SmartBuildParameterPageDecorator extends PageDecorator {

    /** System property toggling the modern Build With Parameters layout (default: on). */
    public static final String ENABLED_PROPERTY = "io.github.xvvx.smartbuildparameter.modernBuildPage";

    public boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    /**
     * True only for the Build With Parameters form page ({@code /job/…/build} GET). The
     * client-side script double-checks for {@code form[name="parameters"]} before doing
     * anything, so a URI misfire stays a no-op.
     */
    public boolean isBuildParametersPage() {
        if (!isEnabled()) {
            return false;
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return req != null && req.getRequestURI().endsWith("/build");
    }

    @Override
    public String getDisplayName() {
        return Messages.SmartBuildParameterPageDecorator_DisplayName();
    }
}
