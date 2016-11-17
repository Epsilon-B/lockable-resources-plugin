package org.jenkins.plugins.lockableresources;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.queue.policy.QueueFifoPolicy;
import org.jenkins.plugins.lockableresources.queue.policy.QueueLifoPolicy;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

public class LockStepTest {
    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();
    @ClassRule
    public static final BuildWatcher buildWatcher = new BuildWatcher();

    @Test
    public void autoCreateResource() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(resource: 'resource1') {\n"
                        + "	echo 'Resource locked'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                story.j.waitForCompletion(b1);
                story.j.assertBuildStatus(Result.SUCCESS, b1);
                story.j.assertLogContains("Resource [resource1] did not exist. Created.", b1);
            }
        });
    }

    @Test
    public void autoCreateResourceWithLabel() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1", "label1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(label: 'label1') {\n"
                        + "	echo 'Resource locked'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                story.j.waitForCompletion(b1);
                story.j.assertBuildStatus(Result.SUCCESS, b1);
                story.j.assertLogContains("Lock released on [label1]", b1);
            }
        });
    }

    @Test
    public void useVariableName() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1", "label1 label2");
                LockableResourcesManager.get().createResource("resource2", "label1 label2");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(label: 'label1', quantity: 2, variable: 'SELECTED_RESOURCES') {\n"
                        + "   def envvars = env.getEnvironment()\n"
                        + "   echo 'During test = ' + envvars['SELECTED_RESOURCES']\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("Lock acquired on [(labels: 'label1', quantity: 2)]", b1);
                story.j.waitForMessage("During test = resource1, resource2", b1);
                story.j.waitForMessage("Finish", b1);
                story.j.waitForCompletion(b1);
                story.j.assertBuildStatus(Result.SUCCESS, b1);
            }
        });
    }

    @Test
    @Issue("JENKINS-34268")
    public void lockMultipleResources() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1", "label1 label2 label3");
                LockableResourcesManager.get().createResource("resource2", "label1 label3");
                LockableResourcesManager.get().createResource("resource3", "label2 label3");
                WorkflowJob p1 = story.j.jenkins.createProject(WorkflowJob.class, "p1");
                p1.setDefinition(new CpsFlowDefinition(
                        "lock(label: 'label1') {\n"
                        + "	semaphore 'wait-inside1'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowJob p2 = story.j.jenkins.createProject(WorkflowJob.class, "p2");
                p2.setDefinition(new CpsFlowDefinition(
                        "lock(label: 'label2') {\n"
                        + "	semaphore 'wait-inside2'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowJob p3 = story.j.jenkins.createProject(WorkflowJob.class, "p3");
                p3.setDefinition(new CpsFlowDefinition(
                        "lock(labels: ['label2', 'label3'], resources: ['resource1', 'resource2']) {\n"
                        + "	semaphore 'wait-inside3'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));

                WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside1/1", b1);
                story.j.waitForMessage("Lock acquired on [label1]", b1);
                story.j.waitForMessage("Lock resources [resource1, resource2]", b1);

                WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("[label2] is locked, waiting...", b2);
                SemaphoreStep.success("wait-inside1/1", null);
                story.j.waitForMessage("Lock released on [label1]", b1);
                story.j.waitForMessage("Unlock resources [resource1, resource2]", b1);
                story.j.waitForMessage("Finish", b1);
                SemaphoreStep.waitForStart("wait-inside2/1", b2);
                story.j.waitForMessage("Lock acquired on [label2]", b2);
                story.j.waitForMessage("Lock resources [resource1, resource3]", b2);

                WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("[label2, label3, resource1, resource2] is locked, waiting...", b3);
                SemaphoreStep.success("wait-inside2/1", null);
                story.j.waitForMessage("Lock released on [label2]", b2);
                story.j.waitForMessage("Unlock resources [resource1, resource3]", b2);
                story.j.waitForMessage("Finish", b2);
                SemaphoreStep.waitForStart("wait-inside3/1", b3);
                story.j.waitForMessage("Lock acquired on [label2, label3, resource1, resource2]", b3);
                story.j.waitForMessage("Lock resources [resource1, resource2, resource3]", b3);
            }
        });
    }

    @Test
    @Issue("JENKINS-34273")
    public void lockOrderLabel() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1", "label1");
                LockableResourcesManager.get().createResource("resource2", "label1");
                LockableResourcesManager.get().createResource("resource3", "label1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(label: 'label1', quantity: 2) {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside/1", b1);

                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                // Ensure that b2 reaches the lock before b3
                story.j.waitForMessage("[(labels: 'label1', quantity: 2)] is locked, waiting...", b2);
                //story.j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b2);
                WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
                // Both 2 and 3 are waiting for locking label: label1, quantity: 2
                story.j.waitForMessage("[(labels: 'label1', quantity: 2)] is locked, waiting...", b3);
                //story.j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b3);

                // Unlock label: label1, quantity: 2
                SemaphoreStep.success("wait-inside/1", null);
                story.j.waitForMessage("Lock released on [(labels: 'label1', quantity: 2)]", b1);
                story.j.waitForMessage("Finish", b1);

                // #2 gets the lock before #3 (in the order as they requested the lock)
                story.j.waitForMessage("Lock acquired on [(labels: 'label1', quantity: 2)]", b2);
                SemaphoreStep.success("wait-inside/2", null);
                story.j.waitForMessage("Finish", b2);
                story.j.waitForMessage("Lock acquired on [(labels: 'label1', quantity: 2)]", b3);
                SemaphoreStep.success("wait-inside/3", null);
                story.j.waitForMessage("Finish", b3);
            }
        });
    }

    @Test
    public void lockOrderLabelQuantity() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1", "label1");
                LockableResourcesManager.get().createResource("resource2", "label1");
                LockableResourcesManager.get().createResource("resource3", "label1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(label: 'label1', quantity: 2) {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside/1", b1);

                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                // Ensure that b2 reaches the lock before b3
                story.j.waitForMessage("[(labels: 'label1', quantity: 2)] is locked, waiting...", b2);
                //story.j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b2);

                WorkflowJob p3 = story.j.jenkins.createProject(WorkflowJob.class, "p3");
                p3.setDefinition(new CpsFlowDefinition(
                        "lock(label: 'label1', quantity: 1) {\n"
                        + "	semaphore 'wait-inside-quantity1'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
                // While 2 continues waiting, 3 can continue directly
                SemaphoreStep.waitForStart("wait-inside-quantity1/1", b3);
                // Let 3 finish
                SemaphoreStep.success("wait-inside-quantity1/1", null);
                story.j.waitForMessage("Finish", b3);

                // Unlock label: label1, quantity: 2
                SemaphoreStep.success("wait-inside/1", null);
                story.j.waitForMessage("Lock released on [(labels: 'label1', quantity: 2)]", b1);

                // #2 gets the lock before #3 (in the order as they requested the lock)
                story.j.waitForMessage("Lock acquired on [(labels: 'label1', quantity: 2)]", b2);
                SemaphoreStep.success("wait-inside/2", null);
                story.j.waitForMessage("Finish", b2);
            }
        });
    }

    @Test
    public void lockOrder() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(resource: 'resource1') {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside/1", b1);

                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                // Ensure that b2 reaches the lock before b3
                story.j.waitForMessage("[resource1] is locked, waiting...", b2);
                WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
                // Both 2 and 3 are waiting for locking resource1

                story.j.waitForMessage("[resource1] is locked, waiting...", b3);

                // Unlock resource1
                SemaphoreStep.success("wait-inside/1", null);
                story.j.waitForMessage("Lock released on [resource1]", b1);

                // #2 gets the lock before #3 (in the order as they requested the lock)
                story.j.waitForMessage("Lock acquired on [resource1]", b2);
                SemaphoreStep.success("wait-inside/2", null);
                story.j.waitForMessage("Lock acquired on [resource1]", b3);
                SemaphoreStep.success("wait-inside/3", null);
                story.j.waitForMessage("Finish", b3);
            }
        });
    }

    @Test
    public void lockNormalOrder() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager manager = LockableResourcesManager.get();
                manager.setQueuePolicy(new QueueFifoPolicy());
                manager.createResource("resource1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(resource: 'resource1') {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside/1", b1);

                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                // Ensure that b2 reaches the lock before b3
                story.j.waitForMessage("[resource1] is locked, waiting...", b2);
                WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
                // Both 2 and 3 are waiting for locking resource1

                story.j.waitForMessage("[resource1] is locked, waiting...", b3);

                // Unlock resource1
                SemaphoreStep.success("wait-inside/1", null);
                story.j.waitForMessage("Lock released on [resource1]", b1);

                // #2 gets the lock before #3 because of FIFO policy
                story.j.waitForMessage("Lock acquired on [resource1]", b2);
                SemaphoreStep.success("wait-inside/2", null);
                story.j.waitForMessage("Lock acquired on [resource1]", b3);
                SemaphoreStep.success("wait-inside/3", null);
                story.j.waitForMessage("Finish", b3);
            }
        });
    }

    @Test
    public void lockInverseOrder() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager manager = LockableResourcesManager.get();
                manager.setQueuePolicy(new QueueLifoPolicy());
                manager.createResource("resource1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(resource: 'resource1') {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside/1", b1);

                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                // Ensure that b2 reaches the lock before b3
                story.j.waitForMessage("[resource1] is locked, waiting...", b2);
                WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
                // Both 2 and 3 are waiting for locking resource1

                story.j.waitForMessage("[resource1] is locked, waiting...", b3);

                // Unlock resource1
                SemaphoreStep.success("wait-inside/1", null);
                story.j.waitForMessage("Lock released on [resource1]", b1);

                // #3 gets the lock before #2 because of LIFO policy
                story.j.waitForMessage("Lock acquired on [resource1]", b3);
                SemaphoreStep.success("wait-inside/2", null);
                story.j.waitForMessage("Lock acquired on [resource1]", b2);
                SemaphoreStep.success("wait-inside/3", null);
                story.j.waitForMessage("Finish", b3);
            }
        });
    }

    @Test
    public void parallelLock() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "parallel (\n"
                        + "a: {\n"
                        + "	echo 'before sleep'\n"
                        + "	sleep 5\n"
                        + "	echo 'after sleep'\n"
                        + "	lock(resource: 'resource1') {\n"
                        + "		sleep 5\n"
                        + "	}\n"
                        + "}, b: {\n"
                        + "	lock(resource: 'resource1') {\n"
                        + "		semaphore 'wait-b'\n"
                        + "	}\n"
                        + "})\n"
                ));

                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-b/1", b1);
                // both messages are in the log because branch b acquired the lock and branch a is waiting to lock
                story.j.waitForMessage("[b] Lock acquired on [resource1]", b1);
                story.j.waitForMessage("[a] [resource1] is locked, waiting...", b1);

                SemaphoreStep.success("wait-b/1", null);

                story.j.waitForMessage("[a] Lock acquired on [resource1]", b1);
            }
        });
    }

    @Test
    public void lockOrderRestart() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(resource: 'resource1') {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside/1", b1);
                story.j.assertLogContains("Lock acquired on [resource1]", b1);
                
                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                // Ensure that b2 reaches the lock before b3
                story.j.waitForMessage("[resource1] is locked, waiting...", b2);
                
                WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
                // Both 2 and 3 are waiting for locking resource1
                story.j.waitForMessage("[resource1] is locked, waiting...", b3);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                assert p!= null;
                WorkflowRun b1 = p.getBuildByNumber(1);
                WorkflowRun b2 = p.getBuildByNumber(2);
                WorkflowRun b3 = p.getBuildByNumber(3);
                
                story.j.waitForMessage("[resource1] is locked, waiting...", b2);
                story.j.waitForMessage("[resource1] is locked, waiting...", b3);

                // Unlock resource1
                SemaphoreStep.success("wait-inside/1", null);
                story.j.waitForMessage("Lock released on [resource1]", b1);
                story.j.waitForMessage("Finish", b1);

                SemaphoreStep.waitForStart("wait-inside/2", b3);
                story.j.assertLogContains("Lock acquired on [resource1]", b2);
                SemaphoreStep.success("wait-inside/2", null);
                story.j.waitForMessage("Lock released on [resource1]", b2);
                story.j.waitForMessage("Finish", b2);
                
                story.j.waitForMessage("Lock acquired on [resource1]", b3);
                SemaphoreStep.waitForStart("wait-inside/3", b3);
                story.j.assertLogContains("Lock acquired on [resource1]", b3);
                SemaphoreStep.success("wait-inside/3", null);
                story.j.waitForMessage("Lock released on [resource1]", b3);
                story.j.waitForMessage("Finish", b3);
            }
        });
    }

    @Test
    public void interoperability() {
        final Semaphore semaphore = new Semaphore(1);
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(resource: 'resource1') {\n"
                        + "	echo 'Locked'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));

                FreeStyleProject f = story.j.createFreeStyleProject("f");
                f.addProperty(new RequiredResourcesProperty(Collections.singletonList(new RequiredResources("resource1", null, 0)), null));
                f.getBuildersList().add(new TestBuilder() {
                    @Override
                    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                        semaphore.acquire();
                        return true;
                    }
                });
                semaphore.acquire();
                f.scheduleBuild2(0).waitForStart();

                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("[resource1] is locked, waiting...", b1);
                semaphore.release();

                // Wait for lock after the freestyle finishes
                story.j.waitForMessage("Lock released on [resource1]", b1);
            }
        });
    }

    @Test
    public void interoperabilityOnRestart() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(resource: 'resource1') {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("Lock resources [resource1]", b1);
                SemaphoreStep.waitForStart("wait-inside/1", b1);

                FreeStyleProject f = story.j.createFreeStyleProject("f");
                f.addProperty(new RequiredResourcesProperty(Collections.singletonList(new RequiredResources("resource1", null, 0)), null));

                f.scheduleBuild2(0);

                while(story.j.jenkins.getQueue().getItems().length != 1) {
                    System.out.println("Waiting for freestyle to be queued...");
                    Thread.sleep(1000);
                }
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                FreeStyleProject f = story.j.jenkins.getItemByFullName("f", FreeStyleProject.class);
                WorkflowRun b1 = p.getBuildByNumber(1);

                // Unlock resource1
                SemaphoreStep.success("wait-inside/1", null);
                story.j.waitForMessage("Lock released on [resource1]", b1);

                FreeStyleBuild fb1;
                while((fb1 = f.getBuildByNumber(1)) == null) {
                    System.out.println("Waiting for freestyle #1 to start building...");
                    Thread.sleep(1000);
                }

                story.j.waitForMessage("Lock resources [resource1]", fb1);
                story.j.waitForMessage("Unlock resources [resource1]", fb1);
            }
        });
    }

    @Issue("JENKINS-36479")
    @Test
    public void hardKillNewBuildClearsLock() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");

                WorkflowJob p1 = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p1.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'locked!'; semaphore 'wait-inside' }"));
                WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("locked!", b1);
                SemaphoreStep.waitForStart("wait-inside/1", b1);

                WorkflowJob p2 = story.j.jenkins.createProject(WorkflowJob.class, "p2");
                p2.setDefinition(new CpsFlowDefinition(
                        "lock('resource1') {\n"
                        + "  semaphore 'wait-inside'\n"
                        + "}"));
                WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();

                // Make sure that b2 is blocked on b1's lock.
                story.j.waitForMessage("[resource1] is locked, waiting...", b2);

                // Now b2 is still sitting waiting for a lock. Create b3 and launch it to clear the lock.
                WorkflowJob p3 = story.j.jenkins.createProject(WorkflowJob.class, "p3");
                p3.setDefinition(new CpsFlowDefinition(
                        "lock('resource1') {\n"
                        + "  semaphore 'wait-inside'\n"
                        + "}"));
                WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("[resource1] is locked, waiting...", b3);

                // Kill b1 hard.
                b1.doKill();
                story.j.waitForMessage("Hard kill!", b1);
                story.j.waitForCompletion(b1);
                story.j.assertBuildStatus(Result.ABORTED, b1);

                // Verify that b2 gets the lock.
                story.j.waitForMessage("Lock acquired on [resource1]", b2);
                SemaphoreStep.success("wait-inside/2", b2);
                // Verify that b2 releases the lock and finishes successfully.
                story.j.waitForMessage("Lock released on [resource1]", b2);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b2));

                // Now b3 should get the lock and do its thing.
                story.j.waitForMessage("Lock acquired on [resource1]", b3);
                SemaphoreStep.success("wait-inside/3", b3);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b3));
            }
        });
    }

    // TODO: Figure out what to do about the IOException thrown during clean up, since we don't care about it. It's just
    // a result of the first build being deleted and is nothing but noise here.
    @Issue("JENKINS-36479")
    @Test
    public void deleteRunningBuildNewBuildClearsLock() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");

                WorkflowJob p1 = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p1.setDefinition(new CpsFlowDefinition(
                        "lock('resource1') {\n"
                        + "  echo 'locked!'\n"
                        + "  semaphore 'wait-inside'\n"
                        + "}"));
                WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("locked!", b1);
                SemaphoreStep.waitForStart("wait-inside/1", b1);

                WorkflowJob p2 = story.j.jenkins.createProject(WorkflowJob.class, "p2");
                p2.setDefinition(new CpsFlowDefinition(
                        "lock('resource1') {\n"
                        + "  semaphore 'wait-inside'\n"
                        + "}"));
                WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();

                Thread.sleep(2000); //Ensure b2 is started before b3 (try to solve flaked results)

                // Now b2 is still sitting waiting for a lock. Create b3 and launch it to clear the lock.
                WorkflowJob p3 = story.j.jenkins.createProject(WorkflowJob.class, "p3");
                p3.setDefinition(new CpsFlowDefinition(
                        "lock('resource1') {\n"
                        + "  semaphore 'wait-inside'\n"
                        + "}"));
                WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();

                // Make sure that b2 is blocked on b1's lock.
                story.j.waitForMessage("[resource1] is locked, waiting...", b2);
                story.j.waitForMessage("[resource1] is locked, waiting...", b3);

                Thread.sleep(3000); //Add a small delay to try to solve flaked results
                System.out.println("Deleting first pipeline build to unlock resources for other pipelines builds");
                try {
                    b1.delete();
                    System.out.println("Deleted without IOException");
                } catch(IOException e) {
                    System.out.println("Deleted with IOException");
                }

                // Verify that b2 gets the lock.
                story.j.waitForMessage("Lock acquired on [resource1]", b2);
                SemaphoreStep.success("wait-inside/2", b2);
                // Verify that b2 releases the lock and finishes successfully.
                story.j.waitForMessage("Lock released on [resource1]", b2);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b2));

                // Now b3 should get the lock and do its thing.
                story.j.waitForMessage("Lock acquired on [resource1]", b3);
                SemaphoreStep.success("wait-inside/3", b3);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b3));
            }
        });
    }
}
