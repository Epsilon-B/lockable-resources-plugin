/*
 * The MIT License
 *
 * Copyright 2016 Eb.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkins.plugins.lockableresources.step;

import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Eb
 */
public class TryLockStep extends LockStep {
    private static final long serialVersionUID = 1L;
    
    public TryLockStep() {
    }
    
    @DataBoundConstructor
    public TryLockStep(String resource) {
        setResource(resource);
    }
    
    @Extension
    public static class DescriptorImpl extends AbstractStepDescriptorImpl {
        public DescriptorImpl() {
            super(TryLockStepExecution.class);
        }

        @Override
        public String getFunctionName() {
            return "trylock";
        }

        @Override
        public String getDisplayName() {
            return "Try to lock shared resource";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }
    }
}