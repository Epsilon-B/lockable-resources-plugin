/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources;

import groovy.lang.Tuple2;
import hudson.EnvVars;
import hudson.Extension;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.actions.ResourceVariableNameAction;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkins.plugins.lockableresources.step.LockStep;
import org.jenkins.plugins.lockableresources.step.LockStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());
    @Exported
    protected Set<LockableResource> resources = new LinkedHashSet<>();
    /** If this option is selected, the plugin will use an internal algorithm to select
     * the free resources based on their capabilities.<br>
     * The resource that has a unique capability among all other resources has less chance
     * to be selected.<br>
     * On the contrary, if a free resource has very common capabilities it will probably be selected
     * <p>
     * This option is highly experimental.
     */
    @Exported
    protected Boolean useFairSelection = false;
    /**
     * Only used when this lockable resource is tried to be locked by {@link LockStep},
     * otherwise (freestyle builds) regular Jenkins queue is used.
     */
    private final List<QueuedContextStruct> queuedContexts = new ArrayList<>();

    @DataBoundConstructor
    public LockableResourcesManager() {
        load();
    }

    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }

    /**
     * For backward compatibility
     *
     * @return
     */
    @Override
    protected XmlFile getConfigFile() {
        File oldFile = new File(Jenkins.getInstance().getRootDir(), "org.jenkins.plugins.lockableresources.LockableResourcesManager.xml");
        if(oldFile.exists()) {
            return new XmlFile(Jenkins.XSTREAM2, oldFile);
        }
        return new XmlFile(Jenkins.XSTREAM2, oldFile);
    }

    @Override
    public synchronized void save() {
        super.save(); //To change body of generated methods, choose Tools | Templates.
    }

    @Exported
    public Set<LockableResource> getResources() {
        return resources;
    }

    @DataBoundSetter
    public void setResources(Set<LockableResource> resources) {
        this.resources = resources;
    }

    @Exported
    public Boolean getUseFairSelection() {
        return useFairSelection;
    }

    @DataBoundSetter
    public void setUseFairSelection(Boolean useFairSelection) {
        this.useFairSelection = useFairSelection;
    }

    public Set<LockableResource> getAllResources() {
        return resources;
    }

    @Override
    public String getDisplayName() {
        return "External Resources";
    }

    public Set<LockableResource> filterFreeResources(Collection<LockableResource> resources) {
        HashSet<LockableResource> free = new HashSet<>();
        for(LockableResource r : resources) {
            if(r.isFree()) {
                free.add(r);
            }
        }
        return free;
    }

    public int getFreeAmount(Collection<LockableResource> resources) {
        return filterFreeResources(resources).size();
    }

    public int getFreeAmount(String labels, @Nullable EnvVars env) {
        Set<LockableResource> res = getResourcesFromCapabilities(ResourceCapability.splitCapabilities(labels), null, env);
        return filterFreeResources(res).size();
    }

    public Set<String> getAllResourceNames() {
        HashSet<String> res = new HashSet<>(resources.size());
        for(LockableResource r : resources) {
            res.add(r.getName());
        }
        return res;
    }

    @Nullable
    public Set<LockableResource> getResourcesFromNames(@Nonnull Collection<String> resourceNames) {
        LinkedHashSet<LockableResource> res = new LinkedHashSet<>(); // keep same ordering as input
        if(resourceNames.isEmpty()) {
            return res;
        }
        LinkedHashSet<String> buffer = new LinkedHashSet<>(resourceNames); // constant remove time
        for(LockableResource r : resources) {
            String name = r.getName();
            if(buffer.contains(name)) {
                res.add(r);
                buffer.remove(name);
                if(buffer.isEmpty()) {
                    return res;
                }
            }
        }
        LOGGER.info("Unknown resources names: " + buffer);
        return null; // At least one resource name is unknown
    }

    public LockableResource getResourceFromName(String resourceName) {
        if(resourceName != null) {
            for(LockableResource resource : resources) {
                if(resourceName.equals(resource.getName())) {
                    return resource;
                }
            }
        }
        return null;
    }

    public Set<String> getInvalidResourceNames(@Nonnull Collection<String> resourceNames) {
        LinkedHashSet<String> buffer = new LinkedHashSet<>(resourceNames);
        for(LockableResource r : resources) {
            String name = r.getName();
            if(buffer.contains(name)) {
                buffer.remove(name);
                if(buffer.isEmpty()) {
                    return buffer;
                }
            }
        }
        return buffer; // At least one resource name is unknown
    }

    public Set<LockableResource> getQueuedResourcesFromProject(String projectFullName) {
        Set<LockableResource> matching = new HashSet<>();
        for(LockableResource r : resources) {
            String queueItemProject = r.getQueueItemProject();
            if((queueItemProject != null) && queueItemProject.equals(projectFullName)) {
                matching.add(r);
            }
        }
        return matching;
    }

    // Adds already selected (in previous queue round) resources to 'selected'
    private Set<LockableResource> getQueuedResources(String projectFullName, long taskId) {
        Set<LockableResource> res = new HashSet<>();
        for(LockableResource resource : resources) {
            // This project might already have something in queue
            String rProject = resource.getQueueItemProject();
            if((rProject != null) && rProject.equals(projectFullName) && resource.isQueuedByTask(taskId)) {
                // this item has queued the resource earlier
                res.add(resource);
            }
        }
        return res;
    }

    public Set<LockableResource> getLockedResourcesFromBuild(Run<?, ?> build) {
        Set<LockableResource> matching = new HashSet<>();
        for(LockableResource r : resources) {
            Run<?, ?> rBuild = r.getBuild();
            if((rBuild != null) && (rBuild == build)) {
                matching.add(r);
            }
        }
        return matching;
    }

    public Boolean isValidLabel(String singleLabel, boolean acceptResourceName) {
        return singleLabel.startsWith("$") || this.getAllLabels(acceptResourceName).contains(singleLabel);
    }

    public Set<String> getAllLabels(boolean withResourceNames) {
        TreeSet<String> labels = new TreeSet<>();
        for(LockableResource r : this.resources) {
            Set<String> r_labels = Utils.splitLabels(r.getLabels());
            labels.addAll(r_labels);
            if(withResourceNames) {
                labels.add(r.getName());
            }
        }
        return labels;
    }

    public Set<ResourceCapability> getAllCapabilities() {
        HashSet<ResourceCapability> capabilities = new HashSet<>();
        for(LockableResource r : this.resources) {
            capabilities.addAll(r.getCapabilities());
            capabilities.add(r.getMyselfAsCapability());
        }
        return capabilities;
    }

    /**
     *
     * @param neededCapabilities
     * @param prohibitedCapabilities
     * @param env                    Used only for Groovy script execution
     *
     * @return
     */
    public Set<ResourceCapability> getCompatibleCapabilities(Collection<ResourceCapability> neededCapabilities, Collection<ResourceCapability> prohibitedCapabilities, @Nullable EnvVars env) {
        TreeSet<ResourceCapability> capabilities = new TreeSet();
        for(LockableResource r : this.resources) {
            if(r.hasCapabilities(neededCapabilities, prohibitedCapabilities, env)) {
                capabilities.addAll(r.getCapabilities());
                capabilities.add(r.getMyselfAsCapability());
            }
        }
        return capabilities;
    }

    /**
     *
     * @param neededCapabilities
     * @param prohibitedCapabilities
     * @param env                    Used only for Groovy script execution
     *
     * @return
     */
    public Set<LockableResource> getResourcesFromCapabilities(@Nullable Collection<ResourceCapability> neededCapabilities, @Nullable Collection<ResourceCapability> prohibitedCapabilities, @Nullable EnvVars env) {
        HashSet<LockableResource> found = new HashSet<>();
        for(LockableResource r : this.resources) {
            if(r.hasCapabilities(neededCapabilities, prohibitedCapabilities, env)) {
                found.add(r);
            }
        }
        return found;
    }

    @Nullable
    public RequiredResourcesProperty getProjectRequiredResourcesProperty(Job<?, ?> project) {
        if(project instanceof MatrixConfiguration) {
            project = (Job<?, ?>) project.getParent();
        }
        return project.getProperty(RequiredResourcesProperty.class);
    }

    @Nullable
    public Set<LockableResource> selectFreeResources(Collection<RequiredResources> requiredResourcesList, Collection<LockableResource> forcedFreeResources, EnvVars env) {
        return selectResources(requiredResourcesList, true, forcedFreeResources, env);
    }

    @Nullable
    public Set<LockableResource> selectResources(Collection<RequiredResources> requiredResourcesList, boolean onlyFreeResources, @Nullable Collection<LockableResource> forcedFreeResources, EnvVars env) {
        Set<LockableResource> res = new HashSet<>();
        //--------------
        // Add resources by names
        //--------------
        for(RequiredResources rr : requiredResourcesList) {
            Set<LockableResource> myResources = rr.getResourcesList(env);
            if(myResources == null) {
                // At least one invalid resource name
                return null;
            }
            if(onlyFreeResources) {
                for(LockableResource r : myResources) {
                    if(r.isFree() || ((forcedFreeResources != null) && forcedFreeResources.contains(r))) {
                        // Resource can be used (free or allowed)
                        res.add(r);
                    } else {
                        // At least one resource not free
                        return null;
                    }
                }
            } else {
                res.addAll(myResources);
            }
        }
        //--------------
        // Add resources by capabilities + quantity
        //--------------
        List<Tuple2<Set<LockableResource>, Integer>> request = new ArrayList<>(requiredResourcesList.size());
        for(RequiredResources rr : requiredResourcesList) {
            Set<ResourceCapability> capabilities = rr.getCapabilitiesList(env);
            if(capabilities.size() > 0) {
                Set<LockableResource> candidates = getResourcesFromCapabilities(capabilities, null, env);
                candidates.removeAll(res); // Already selected by names: can be re-use for capabilities selection
                Set<LockableResource> freeCandidates;
                if(onlyFreeResources) {
                    freeCandidates = filterFreeResources(candidates);
                    if(forcedFreeResources != null) {
                        freeCandidates.addAll(forcedFreeResources);
                        freeCandidates.retainAll(candidates);
                    }
                } else {
                    freeCandidates = candidates;
                }
                if(rr.quantity <= 0) {
                    // Note: freeCandidates.size() <= candidates.size()
                    if(freeCandidates.size() == candidates.size()) {
                        // Use all resources of this type
                        res.addAll(freeCandidates);
                    } else {
                        // At least one resource is locked/queued: exit
                        return null;
                    }
                } else {
                    if(useFairSelection) {
                        // Sort resources
                        freeCandidates = sortResources(freeCandidates, env);
                    }
                    request.add(new Tuple2<>(freeCandidates, rr.quantity));
                }
            }
        }
        Set<LockableResource> selected = selectAmongPossibilities(request);
        if(selected == null) {
            // No enough free resources with given capabilities
            return null;
        }
        // Merge resources by names and by capabilities
        res.addAll(selected);
        return res;
    }

    private static class SortComparator implements Comparator<Tuple2<LockableResource, Double>>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public int compare(Tuple2<LockableResource, Double> o1, Tuple2<LockableResource, Double> o2) {
            return o1.getSecond().compareTo(o2.getSecond());
        }
    }

    public LinkedHashSet<LockableResource> sortResources(Collection<LockableResource> resources, EnvVars env) {
        // Extract the best resources based on their capabilities
        List<Tuple2<LockableResource, Double>> sortedFree = new ArrayList<>(); // (resource, cost)
        for(LockableResource r : resources) {
            Set<ResourceCapability> rc = r.getCapabilities();
            Set<LockableResource> compatibles = getResourcesFromCapabilities(rc, null, env);
            int nFree = getFreeAmount(compatibles);
            int nMax = compatibles.size();
            double cost = (nMax - nFree) * (resources.size() / nMax) + rc.size();
            sortedFree.add(new Tuple2<>(r, cost));
        }
        Collections.sort(sortedFree, new SortComparator());
        LOGGER.finer("Costs for using resources:");
        LinkedHashSet<LockableResource> res = new LinkedHashSet();
        for(Tuple2<LockableResource, Double> t : sortedFree) {
            res.add(t.getFirst());
            LOGGER.info(" - " + t.getFirst().getName() + ": " + t.getSecond());
        }
        return res;
    }

    /**
     * Try to lock required resources.<br>
     * If not possible, put context in queue for next try
     *
     * @param step
     * @param context
     *
     * @return True if locked, False if queued for later
     */
    public synchronized boolean lockOrQueueForLater(LockStep step, StepContext context) {
        Run<?, ?> run;
        try {
            run = context.get(Run.class);
        } catch(IOException | InterruptedException ex) {
            return false;
        }
        TaskListener listener;
        try {
            listener = context.get(TaskListener.class);
        } catch(IOException | InterruptedException ex) {
            listener = null;
        }
        EnvVars env = Utils.getEnvVars(run, listener);
        Collection<RequiredResources> required = step.getRequiredResources();
        Set<LockableResource> selected = selectFreeResources(required, null, env);
        if(lock(selected, required, run, context, step.getInversePrecedence(), step.getVariable())) {
            return true;
        } else {
            if(listener != null) {
                listener.getLogger().println(step + " is locked, waiting...");
            }
            queueContext(context, step);
            return false;
        }
    }

    /**
     * Try to lock the resource and return true if locked.
     *
     * @param resources
     * @param build
     * @param context
     * @param requiredresources
     * @param inversePrecedence
     * @param variableName
     *
     * @return
     */
    public synchronized boolean lock(Collection<LockableResource> resources,
            Collection<RequiredResources> requiredresources,
            @Nonnull Run<?, ?> build, @Nullable StepContext context,
            boolean inversePrecedence, @Nullable String variableName) {
        if(resources == null) {
            return false;
        }
        for(LockableResource r : resources) {
            if(!r.canLock()) {
                // At least one resource is not available: abort lock process
                save();
                return false;
            }
        }
        LOGGER.info("Locking resources " + resources);
        for(LockableResource r : resources) {
            r.unqueue();
            r.setBuild(build);
        }
        if(variableName != null) {
            String lbl = Utils.getParameterValue(resources);
            build.addAction(new ResourceVariableNameAction(new StringParameterValue(variableName, lbl)));
        }
        if(context != null) {
            // since LockableResource contains transient variables, they cannot be correctly serialized
            // hence we use their unique resource names
            ArrayList<String> resourceNames = new ArrayList<>();
            for(LockableResource resource : resources) {
                resourceNames.add(resource.getName());
            }
            // Sort names for predictable logs (for tests)
            Collections.sort(resourceNames);
            LockStepExecution.proceed(resourceNames, requiredresources, context, inversePrecedence);
        }
        save();
        return true;
    }

    private synchronized void unlockResources(@Nonnull Collection<LockableResource> unlockResources, @Nullable Run<?, ?> build) {
        LOGGER.info("Unlocking resources " + unlockResources);
        if(build == null) {
            for(LockableResource resource : unlockResources) {
                if((resource != null) && resource.isLocked()) {
                    // No more contexts, unlock resource
                    resource.unqueue();
                    resource.setBuild(null);
                }
            }
        } else {
            for(LockableResource resource : unlockResources) {
                if((resource != null) && resource.isLockedByBuild(build)) {
                    // No more contexts, unlock resource
                    resource.unqueue();
                    resource.setBuild(null);
                }
            }
        }
    }

    public synchronized void unlock(@Nonnull Collection<LockableResource> resourcesToUnLock) {
        unlock(resourcesToUnLock, null, null, false);
    }

    public synchronized void unlock(@Nonnull Collection<LockableResource> resourcesToUnLock, @Nullable Run<?, ?> build) {
        unlock(resourcesToUnLock, build, null, false);
    }

    public synchronized void unlock(@Nonnull Collection<LockableResource> resourcesToUnLock,
            @Nullable Run<?, ?> build, @Nullable StepContext context,
            boolean inversePrecedence) {
        //--------------------------
        // Free old resources no longer needed
        //--------------------------
        unlockResources(resourcesToUnLock, build);

        //--------------------------
        // Check if there are works waiting for resources
        //--------------------------
        QueuedContextStruct nextContext = getNextQueuedContext(resourcesToUnLock, inversePrecedence);
        if(nextContext == null) {
            // no context is queued which can be started once these resources are free'd.
            save();
            return;
        }

        //--------------------------
        // resourcesToUnLock: contains the previous resources (still locked).
        // requiredResourceForNextContext: contains the resource objects which are required for the next context.
        //--------------------------
        EnvVars env = Utils.getEnvVars(context);
        LockStep step = nextContext.getStep();
        Collection<RequiredResources> requiredResourcesForNextContext = step.getRequiredResources();
        Set<LockableResource> resourcesForNextContext = selectFreeResources(requiredResourcesForNextContext, resourcesToUnLock, env);
        if(resourcesForNextContext != null) {
            //--------------------------
            // Remove context from queue and process it
            //--------------------------
            this.queuedContexts.remove(nextContext);

            //--------------------------
            // Lock new resources
            //--------------------------
            Run<?, ?> nextBuild = nextContext.getBuild();
            StepContext nextContextContext = nextContext.getContext();
            if(nextBuild != null) {
                lock(resourcesForNextContext, requiredResourcesForNextContext, nextBuild, nextContextContext, step.getInversePrecedence(), step.getVariable());
            }
        }
        save();
    }

    private synchronized QueuedContextStruct getNextQueuedContext(Collection<LockableResource> resourceToUnLock, boolean inversePrecedence) {
        if(this.queuedContexts == null) {
            return null;
        }
        QueuedContextStruct newestEntry = null;
        if(!inversePrecedence) {
            for(QueuedContextStruct context : this.queuedContexts) {
                EnvVars env = Utils.getEnvVars(context.getContext());
                LockStep step = context.getStep();
                if(selectFreeResources(step.getRequiredResources(), resourceToUnLock, env) != null) {
                    return context;
                }
            }
        } else {
            long newest = 0;
            for(QueuedContextStruct context : this.queuedContexts) {
                EnvVars env = Utils.getEnvVars(context.getContext());
                LockStep step = context.getStep();
                if(selectFreeResources(step.getRequiredResources(), resourceToUnLock, env) != null) {
                    Run<?, ?> run = context.getBuild();
                    if((run != null) && (run.getStartTimeInMillis() > newest)) {
                        newest = run.getStartTimeInMillis();
                        newestEntry = context;
                    }
                }
            }
        }
        return newestEntry;
    }

    /**
     * Creates the resource if it does not exist.
     *
     * @param name
     *
     * @return
     */
    public synchronized boolean createResource(String name) {
        return createResource(name, null);
    }

    public synchronized boolean createResource(String name, String capabilities) {
        LockableResource existent = getResourceFromName(name);
        if(existent == null) {
            LockableResource resource = new LockableResource(name, capabilities);
            resources.add(resource);
            save();
            return true;
        }
        return false;
    }

    public synchronized boolean reserve(List<LockableResource> resources, String userName) {
        for(LockableResource resource : resources) {
            if(!resource.isFree()) {
                return false;
            }
        }
        for(LockableResource resource : resources) {
            resource.setReservedBy(userName);
        }
        save();
        return true;
    }

    public synchronized void unreserve(List<LockableResource> resources) {
        for(LockableResource resource : resources) {
            resource.unReserve();
        }
        save();
    }

    public synchronized void reset(List<LockableResource> resources) {
        for(LockableResource resource : resources) {
            resource.reset();
        }
        save();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        try {
            LOGGER.fine("Entering manager.configure()");
            List<LockableResource> newResources = req.bindJSONToList(LockableResource.class, json.get("resources"));
            for(LockableResource resource : newResources) {
                LockableResource oldResource = getResourceFromName(resource.getName());
                if(oldResource != null) {
                    resource.setBuild(oldResource.getBuild());
                    resource.setQueued(resource.getQueueItemId(), resource.getQueueItemProject());
                }
            }
            this.resources.clear();
            this.resources.addAll(newResources);
            this.useFairSelection = json.getBoolean("useFairSelection");
            save();
            return true;
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            return false;
        }
    }

    /**
     * Queue lockable resources for later lock
     * Get already queued item from the same project and build and try to complete the selection
     * If all required resources are not available, then previously queued items are released
     * <p>
     * Requires a queue item (for example with a build of a standard freestyle project)
     *
     * @param project
     * @param item
     *
     * @return Return the list of queued resources, or null if no enough resources
     */
    public synchronized Set<LockableResource> queue(Job<?, ?> project, Queue.Item item) {
        final long taskId = item.getId();
        final String projectFullName = project.getFullName();
        final EnvVars env = Utils.getEnvVars(item);

        RequiredResourcesProperty property = getProjectRequiredResourcesProperty(project);
        if(property == null) {
            return Collections.emptySet();
        }
        Collection<RequiredResources> requiredResourcesList = property.getRequiredResourcesList();
        LOGGER.finest(projectFullName + " trying to get resources with these details: " + requiredResourcesList);

        if(isAnotherBuildWaitingResources(projectFullName, taskId)) {
            // The project has another buildable item waiting -> bail out
            LOGGER.log(Level.FINEST, "{0} has another build waiting resources."
                    + " Waiting for it to proceed first.",
                    new Object[] {projectFullName});
            return null;
        }
        Set<LockableResource> alreadyQueued = getQueuedResources(projectFullName, taskId);

        LOGGER.finest(projectFullName + ": trying to get resources with these details: " + requiredResourcesList);
        Set<LockableResource> selected = new HashSet<>(alreadyQueued);
        selected = selectFreeResources(requiredResourcesList, selected, env);

        if(selected == null) {
            // No enough resources for this build: unqueue all associated resources
            for(LockableResource r : alreadyQueued) {
                r.unqueue();
            }
        } else {
            // Resources for this build have been selected: queue all associated resources
            for(LockableResource r : selected) {
                r.setQueued(taskId, projectFullName);
            }
        }
        return selected;
    }

    // Return false if another item queued for this project -> bail out
    private boolean isAnotherBuildWaitingResources(String projectFullName, long taskId) {
        for(LockableResource resource : resources) {
            // This project might already have something in queue
            String rProject = resource.getQueueItemProject();
            if(rProject != null && rProject.equals(projectFullName)) {
                if(!resource.isQueuedByTask(taskId)) {
                    // The project has another buildable item waiting -> bail out
                    LOGGER.log(Level.FINEST, "{0} has another build "
                            + "that already queued resource {1}. Continue queueing.",
                            new Object[] {projectFullName, resource});
                    return true;
                }
            }
        }
        return false;
    }

    /*
     * Adds the given context and the required resources to the queue if
     * this context is not yet queued.
     */
    public synchronized void queueContext(StepContext context, LockStep step) {
        for(QueuedContextStruct entry : this.queuedContexts) {
            if(entry.getContext() == context) {
                return;
            }
        }
        this.queuedContexts.add(new QueuedContextStruct(context, step));
        save();
    }

    public synchronized boolean unqueueContext(StepContext context) {
        for(Iterator<QueuedContextStruct> iter = this.queuedContexts.listIterator(); iter.hasNext();) {
            if(iter.next().getContext() == context) {
                iter.remove();
                save();
                return true;
            }
        }
        return false;
    }

    /**
     * Brute force recursive algorithm to find a set of resources matching the request
     *
     * @param <T>
     * @param request
     *
     * @return
     */
    public static <T> Set<T> selectAmongPossibilities(List<Tuple2<Set<T>, Integer>> request) {
        //--------------------
        // Easy case: solution found
        //--------------------
        if(request.size() <= 0) {
            return new HashSet<>();
        }
        //--------------------
        // Select first sub-request
        // Try to select each candidate and perform recursive call with adapter request
        //--------------------
        Tuple2<Set<T>, Integer> subRequest = request.get(0);
        Set<T> candidates = subRequest.getFirst();
        int nb = subRequest.getSecond();
        if(nb <= 0) {
            // No resource needed: remove the sub-request and continue with others
            List<Tuple2<Set<T>, Integer>> newRequest = new ArrayList<>(request);
            newRequest.remove(subRequest);
            return selectAmongPossibilities(newRequest);
        } else if(nb <= candidates.size()) {
            // Select each candidate, remove it from all other pools in subrequests, then perform recursive call
            List<Tuple2<Set<T>, Integer>> newRequest = new ArrayList<>(request.size());
            for(T v : candidates) {
                newRequest.clear();
                for(Tuple2<Set<T>, Integer> sr : request) {
                    Set<T> newCandidates = new HashSet<>(sr.getFirst());
                    newCandidates.remove(v);
                    int newNb = ((sr == subRequest) ? nb - 1 : sr.getSecond());
                    newRequest.add(new Tuple2<>(newCandidates, newNb));
                }
                Set<T> res = selectAmongPossibilities(newRequest);
                if(res != null) {
                    res.add(v);
                    return res;
                }
            }
        }
        // No solution
        return null;
    }

    public static LockableResourcesManager get() {
        Jenkins jenkins = Jenkins.getInstance();
        if(jenkins != null) {
            return (LockableResourcesManager) jenkins.getDescriptorOrDie(LockableResourcesManager.class);
        }
        throw new IllegalStateException("Jenkins instance has not been started or was already shut down.");
    }
}
