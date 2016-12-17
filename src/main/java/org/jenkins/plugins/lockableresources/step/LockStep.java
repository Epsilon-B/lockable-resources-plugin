package org.jenkins.plugins.lockableresources.step;

import com.google.common.collect.Lists;
import hudson.Extension;
import hudson.Util;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

/**
 * Job step that can be added to a pipeline project
 * The lock/unlock process will be handled with LockStepExecution
 *
 * @author
 */
public class LockStep extends AbstractStepImpl implements Serializable {
    private static final long serialVersionUID = 1L;
    @Exported
    protected String variable = null;
    @Exported
    protected Collection<RequiredResources> requiredResourcesList = new ArrayList<>();
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient final String resource = null;

    public LockStep() {
    }

    // it should be LockStep() - without params. But keeping this for backward compatibility
	// so `lock('resource1')` still works and `lock(label: 'label1', quantity: 3)` works too (resource is not required)
	@DataBoundConstructor
    public LockStep(String resource) {
        setResource(resource);
    }

    @DataBoundSetter
    @SuppressWarnings("FinalMethod")
    public final void setResource(String resource) {
        if(resource != null && !resource.isEmpty()) {
            if(requiredResourcesList == null || requiredResourcesList.isEmpty()) {
                requiredResourcesList = Lists.newArrayList(new RequiredResources(resource, null, 0));
            } else {
                RequiredResources rr = requiredResourcesList.iterator().next();
                rr.setResources(resource);
            }
        }
    }

    @DataBoundSetter
    public void setResources(Collection<String> resources) {
        if(resources != null) {
            for(String myResource : resources) {
                RequiredResources rr = new RequiredResources(myResource, null, 0);
                if(requiredResourcesList == null || requiredResourcesList.isEmpty()) {
                    requiredResourcesList = Lists.newArrayList(rr);
                } else {
                    requiredResourcesList.add(rr);
                }
            }
        }
    }

    @DataBoundSetter
    public void setLabel(String label) {
        if(label != null && !label.isEmpty()) {
            if(requiredResourcesList == null || requiredResourcesList.isEmpty()) {
                requiredResourcesList = Lists.newArrayList(new RequiredResources(null, label, 0));
            } else {
                RequiredResources rr = requiredResourcesList.iterator().next();
                rr.setLabels(label);
            }
        }
    }

    @DataBoundSetter
    public void setCapability(String capability) {
        setLabel(capability);
    }

    @DataBoundSetter
    public void setCapabilities(Collection<String> capabilities) {
        setLabels(capabilities);
    }

    @DataBoundSetter
    public void setLabels(Collection<String> labels) {
        if(labels != null) {
            for(String label : labels) {
                RequiredResources rr = new RequiredResources(null, label, 0);
                if(requiredResourcesList == null || requiredResourcesList.isEmpty()) {
                    requiredResourcesList = Lists.newArrayList(rr);
                } else {
                    requiredResourcesList.add(rr);
                }
            }
        }
    }

    @DataBoundSetter
    public void setQuantity(Integer quantity) {
        if(quantity != null) {
            if(requiredResourcesList == null || requiredResourcesList.isEmpty()) {
                // do nothing
            } else {
                RequiredResources rr = requiredResourcesList.iterator().next();
                rr.setQuantity(quantity);
            }
        }
    }

    @DataBoundSetter
    public void setRequiredResources(Collection<RequiredResources> requiredResourcesList) {
        this.requiredResourcesList.clear();
        this.requiredResourcesList.addAll(requiredResourcesList);
    }

    @Exported
    public Collection<RequiredResources> getRequiredResources() {
        return Collections.unmodifiableCollection(requiredResourcesList);
    }

    @DataBoundSetter
    public void setVariable(String variable) {
        this.variable = variable;
    }

    @Exported
    public String getVariable() {
        return this.variable;
    }

    @Override
    public String toString() {
        // An exact format is currently needed for tests
        if(requiredResourcesList == null) {
            return "";
        }
        return requiredResourcesList.toString();
    }

    /**
     * Magically called when imported from XML file
     * Manage backward compatibility
     *
     * @return
     */
    public Object readResolve() {
        if(resource != null) {
            requiredResourcesList = new ArrayList<>();
            requiredResourcesList.add(new RequiredResources(resource, "", 1));
        }
        return this;
    }

    @Extension
    public static class DescriptorImpl extends AbstractStepDescriptorImpl {
        public DescriptorImpl() {
            super(LockStepExecution.class);
        }

        @Override
        public String getFunctionName() {
            return "lock";
        }

        @Override
        public String getDisplayName() {
            return "Lock shared resource";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }
		
		public static FormValidation doCheckLabel(@QueryParameter String value, @QueryParameter String resource) {
			String resourceLabel = Util.fixEmpty(value);
			String resourceName = Util.fixEmpty(resource);
            // In API 2.0, both resourceLabel + resourceName can be set at the same time
            // => resourceName is selected first, then (resourceLabel + quantity)
			/*if (resourceLabel != null && resourceName != null) {
				return FormValidation.error("Label and resource name cannot be specified simultaneously.");
			}*/
			if ((resourceLabel == null) && (resourceName == null)) {
				return FormValidation.error("Either label or resource name must be specified.");
			}
			return FormValidation.ok();
        }
		
		public static FormValidation doCheckResource(@QueryParameter String value, @QueryParameter String label) {
			return doCheckLabel(label, value);
		}
    }
}
