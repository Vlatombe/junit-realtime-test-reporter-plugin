/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
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

package org.jenkinsci.plugins.junitrealtimetestreporter;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.test.TestResult;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;

/**
 * Attaches distinct test result actions to each {@code node} (or {@code ws}) block in a job with this plugin activated.
 * Looks for all {@code junit} steps in the {@code lastSuccessfulBuild} so it can guess at test patterns to archive.
 */
@Extension
public class PipelineAttacher implements GraphListener {

    private static final Logger LOGGER = Logger.getLogger(PipelineAttacher.class.getName());

    @Override
    public void onNewHead(FlowNode node) {
        if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, "notified of {0} ~ {1}", new Object[] {node, node.getDisplayFunctionName()});
        }
        if (node instanceof BlockStartNode) {
            for (FlowNode node2 : node.getParents()) { // do not pay attention until the body is invoked, when WorkspaceAction is attached
                LOGGER.log(Level.FINER, "considering of {0} ~ {1}", new Object[] {node2, node2.getDisplayFunctionName()});
                WorkspaceAction wsa = node2.getPersistentAction(WorkspaceAction.class);
                if (wsa != null) {
                    FilePath workspace = wsa.getWorkspace();
                    if (workspace == null) {
                        LOGGER.fine("no workspace");
                        return;
                    }
                    Queue.Executable executable;
                    try {
                        executable = node2.getExecution().getOwner().getExecutable();
                    } catch (IOException x) {
                        LOGGER.log(Level.WARNING, null, x);
                        return;
                    }
                    if (executable instanceof Run) {
                        Run<?, ?> run = (Run<?, ?>) executable;
                        Job<?, ?> job = run.getParent();
                        if (!PerJobConfiguration.isActive(job)) {
                            LOGGER.log(Level.FINE, "inactive in {0}", job);
                            return;
                        }
                        LOGGER.log(Level.FINE, "encountered {0} in {1} entering {2}", new Object[] {workspace, run, node2.getDisplayFunctionName()});
                        Run<?, ?> last = job.getLastSuccessfulBuild();
                        if (!(last instanceof FlowExecutionOwner.Executable)) {
                            LOGGER.log(Level.FINE, "no lastSuccessfulBuild in {0}", job);
                            return;
                        }
                        FlowExecutionOwner owner = ((FlowExecutionOwner.Executable) last).asFlowExecutionOwner();
                        if (owner == null) {
                            LOGGER.log(Level.WARNING, "could not get FlowExecutionOwner from {0}", last);
                            return;
                        }
                        FlowExecution exec = owner.getOrNull();
                        if (exec == null) {
                            LOGGER.log(Level.WARNING, "could not get FlowExecution from {0}", owner);
                            return;
                        }
                        boolean keepLongStdio = false;
                        StringBuilder glob = null;
                        for (FlowNode n : new DepthFirstScanner().allNodes(exec)) {
                            if (n instanceof StepNode && ((StepNode) n).getDescriptor().getFunctionName().equals("step")) {
                                Object delegate = ArgumentsAction.getResolvedArguments(n).get("delegate");
                                if (delegate instanceof UninstantiatedDescribable) {
                                    UninstantiatedDescribable ud = (UninstantiatedDescribable) delegate;
                                    DescribableModel<?> model = ud.getModel();
                                    if (model != null && model.getType() == JUnitResultArchiver.class) {
                                        try {
                                            JUnitResultArchiver archiver = ud.instantiate(JUnitResultArchiver.class);
                                            keepLongStdio |= archiver.isKeepLongStdio();
                                            if (glob == null) {
                                                glob = new StringBuilder();
                                            } else {
                                                glob.append(',').append(archiver.getTestResults());
                                            }
                                        } catch (Exception x) {
                                            LOGGER.log(Level.WARNING, null, x);
                                        }
                                    }
                                }
                            }
                        }
                        if (glob != null) {
                            LOGGER.log(Level.FINE, "seem to be recording ‘{0}’ in {1}", new Object[] {glob, job});
                            run.addAction(new PipelineRealtimeTestResultAction(node2.getId(), workspace, keepLongStdio, glob.toString()));
                            AbstractRealtimeTestResultAction.saveBuild(run);
                        } else {
                            LOGGER.log(Level.FINE, "no junit recorded in {0}", run);
                        }
                    }
                } else {
                    LOGGER.finer("no WorkspaceAction");
                }
            }
        } else if (node instanceof BlockEndNode) {
            BlockStartNode startNode = ((BlockEndNode) node).getStartNode();
            if (startNode.getPersistentAction(WorkspaceAction.class) == null) {
                LOGGER.finer("no WorkspaceAction");
                return; // shortcut
            }
            String startNodeId = startNode.getId();
            Queue.Executable executable;
            try {
                executable = node.getExecution().getOwner().getExecutable();
            } catch (IOException x) {
                LOGGER.log(Level.WARNING, null, x);
                return;
            }
            if (executable instanceof Run) {
                Run<?, ?> run = (Run<?, ?>) executable;
                for (PipelineRealtimeTestResultAction a : run.getActions(PipelineRealtimeTestResultAction.class)) {
                    if (a.startNodeId.equals(startNodeId)) {
                        run.removeAction(a);
                        LOGGER.log(Level.FINE, "clearing {0}", node);
                        AbstractRealtimeTestResultAction.saveBuild(run);
                        break;
                    }
                }
            } else {
                LOGGER.log(Level.WARNING, "not a Run: {0}", executable);
            }
        }
    }

    private static class PipelineRealtimeTestResultAction extends AbstractRealtimeTestResultAction {

        private final String startNodeId;
        private final String node;
        private final String workspace;
        private final boolean keepLongStdio;
        private final String glob;

        PipelineRealtimeTestResultAction(String startNodeId, FilePath ws, boolean keepLongStdio, String glob) {
            this.startNodeId = startNodeId;
            node = FilePathUtils.getNodeName(ws);
            workspace = ws.getRemote();
            this.keepLongStdio = keepLongStdio;
            this.glob = glob;
        }

        @Override
        protected TestResult parse() throws IOException, InterruptedException {
            FilePath ws = FilePathUtils.find(node, workspace);
            if (ws != null && ws.isDirectory()) {
                LOGGER.log(Level.FINE, "parsing ‘{0}’ in {1} on node {2} for {3}", new Object[] {glob, workspace, node, run});
                return new JUnitParser(keepLongStdio, true).parseResult(glob, run, ws, null, null);
            } else {
                LOGGER.log(Level.FINE, "skipping parse in nonexistent workspace for {0}", run);
                return new hudson.tasks.junit.TestResult();
            }
        }

    }

}
