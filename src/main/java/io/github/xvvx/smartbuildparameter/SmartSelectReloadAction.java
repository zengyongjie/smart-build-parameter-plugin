package io.github.xvvx.smartbuildparameter;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.RootAction;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.json.JsonHttpResponse;

/**
 * Stable URL for the smartSelect build-page "retry" entry (FR-3 行为规范 4):
 * {@code /smart-build-parameter/reloadOptions?job=FULLNAME&name=PARAM} re-runs the parameter's
 * source (bypassing its TTL cache) and answers fresh options — or the structured error — as JSON.
 *
 * <p>Not a {@code /descriptorByName/...} URL on purpose: descriptor-by-name routing is awkward
 * for nested {@code $DescriptorImpl} classes, and this endpoint needs the job anyway.</p>
 */
@Extension
public class SmartSelectReloadAction implements RootAction {

    private static final Logger LOGGER = Logger.getLogger(SmartSelectReloadAction.class.getName());

    static final String URL_NAME = "smart-build-parameter";

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    @Override
    public String getIconFileName() {
        return null; // not a sidebar item
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    public HttpResponse doReloadOptions(@QueryParameter String job, @QueryParameter String name) {
        JSONObject data = new JSONObject();
        Job<?, ?> j = job == null || job.isBlank() ? null : Jenkins.get().getItemByFullName(job, Job.class);
        if (j == null) {
            data.put("error", Messages.SmartSelectParameterDefinition_Reload_unknown_job());
            return new JsonHttpResponse(data);
        }
        j.checkPermission(Item.READ);
        ParametersDefinitionProperty property = j.getProperty(ParametersDefinitionProperty.class);
        ParameterDefinition def = property == null ? null : property.getParameterDefinition(name);
        if (!(def instanceof SmartSelectParameterDefinition smart) || smart.getSource() == null) {
            data.put("error", Messages.SmartSelectParameterDefinition_Reload_unknown_parameter());
            return new JsonHttpResponse(data);
        }
        try {
            data.put("options", JSONArray.fromObject(smart.getSource().fetch(true)));
            return new JsonHttpResponse(data);
        } catch (SmartSelectSource.SmartSourceException e) {
            LOGGER.log(Level.FINE, () -> "smartSelect '" + name + "' retry failed: " + e.getMessage());
            data.put("error", e.getMessage());
            return new JsonHttpResponse(data);
        }
    }
}
