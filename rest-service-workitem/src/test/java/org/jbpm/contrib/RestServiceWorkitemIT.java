/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jbpm.contrib;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jbpm.contrib.demoservices.Service;
import org.jbpm.contrib.mockserver.OneTaskProcess;
import org.jbpm.contrib.mockserver.WorkItems;
import org.jbpm.contrib.restservice.Constant;
import org.jbpm.contrib.restservice.SimpleRestServiceWorkItemHandler;
import org.jbpm.process.workitem.WorkDefinitionImpl;
import org.jbpm.process.workitem.WorkItemRepository;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.kie.api.event.process.DefaultProcessEventListener;
import org.kie.api.event.process.ProcessCompletedEvent;
import org.kie.api.event.process.ProcessEventListener;
import org.kie.api.event.process.ProcessNodeTriggeredEvent;
import org.kie.api.event.process.ProcessVariableChangedEvent;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.api.runtime.process.WorkItemManager;
import org.kie.api.runtime.process.WorkflowProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.jbpm.contrib.restservice.Constant.KIE_HOST_SYSTEM_PROPERTY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 * @author Ryszard Kozmik
 */
public class RestServiceWorkitemIT extends JBPMBase {

    private static int PORT = 8080;
    private static String DEFAULT_HOST = "localhost";
    private final Logger logger = LoggerFactory.getLogger(RestServiceWorkitemIT.class);

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private ObjectMapper objectMapper = new ObjectMapper();

    protected static KieSession currentKieSession;

    public RestServiceWorkitemIT() {
        super(true, true);
    }

    @BeforeClass
    public static void mainSetUp() throws Exception {
        bootUpServices();
    }

    @Before
    public void preTestSetup() {
        System.setProperty(KIE_HOST_SYSTEM_PROPERTY, "localhost:8080");

        // Configure jBPM server with all the test processes, workitems and event listeners.
        setupPoolingDataSource();
        
        RuntimeManager manager = createRuntimeManager( new OneTaskProcess().getProcess() );
        RuntimeEngine runtimeEngine = getRuntimeEngine();
        
        KieSession kieSession = runtimeEngine.getKieSession();
        kieSession.addEventListener(new RestServiceProcessEventListener());

        WorkItemManager workItemManager = kieSession.getWorkItemManager();
        workItemManager.registerWorkItemHandler("SimpleRestService", new SimpleRestServiceWorkItemHandler(manager));

        currentKieSession = kieSession;
    }
    
    @After
    public void postTestTeardown() {
        currentKieSession=null;
    }
    
    // TODO: This is a bit of a hack. It works but such sharing of tested instance through static field is questionable.
    public static void completeWorkItem( int workItemId, Map<String, Object> result ) {
        currentKieSession.getWorkItemManager().completeWorkItem(workItemId, result);
    }
    
    // TODO: This is a bit of a hack. It works but such sharing of tested instance through static field is questionable.
    public static void sendSignalToProcess( long processId, String signalName, Map<String, Object> result ) {
        currentKieSession.signalEvent(signalName, result, processId);
    }
    
    private static void bootUpServices() throws Exception {
        ContextHandlerCollection contexts = new ContextHandlerCollection();

        final Server server = new Server(PORT);
        server.setHandler(contexts);

        ServletContextHandler demoService = new ServletContextHandler(contexts, "/demo-service", ServletContextHandler.SESSIONS);

        ServletHolder servletHolder = demoService.addServlet(org.glassfish.jersey.servlet.ServletContainer.class, "/*");
        servletHolder.setInitOrder(0);
        // Tells the Jersey Servlet which REST service/class to load.
        servletHolder.setInitParameter("jersey.config.server.provider.classnames", Service.class.getCanonicalName());

        // JBPM server mock
        ServletContextHandler jbpmMock = new ServletContextHandler(contexts, "/kie-server/services/rest", ServletContextHandler.SESSIONS);
        ServletHolder jbpmMockServlet = jbpmMock.addServlet(org.glassfish.jersey.servlet.ServletContainer.class, "/*");
        jbpmMockServlet.setInitOrder(0);
        jbpmMockServlet.setInitParameter("jersey.config.server.provider.classnames", WorkItems.class.getCanonicalName());
        server.start();
    }

    @Test (timeout=15000)
    public void shouldInvokeRemoteServiceAndReceiveCallback() throws InterruptedException, JsonProcessingException {

        BlockingQueue<ProcessVariableChangedEvent> queue = new ArrayBlockingQueue(1000);

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                String variableId = event.getVariableId();
                logger.info("Process: {}, variable: {}, changed to: {}.",
                        event.getProcessInstance().getProcessName(), variableId,
                        event.getNewValue());
                if ("resultA".equals(variableId)) {
                    queue.add(event);
                }
            }
        };

        KieSession kieSession = getRuntimeEngine().getKieSession();
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("containerId", "mock");
        Map<String, Object> input = new HashMap<>();
        input.put("username", "Matej");
        parameters.put("input", input);

        //when
        kieSession.startProcess("org.jbpm.oneTaskProcess", parameters);

        //then
        Map<String, Object> callbackResult  = (Map<String, Object>) queue.take().getNewValue();
        Map<String, Object> content = (Map<String, Object>) callbackResult.get("content");
        Map<String, Object> person = (Map<String, Object>) content.get("person");
        Assert.assertEquals("Matej", person.get("name"));

        kieSession.removeEventListener(processEventListener);
    }

    @Test @Ignore
    public void testCancel() throws InterruptedException {
        KieSession kieSession = getRuntimeEngine().getKieSession();

        final Semaphore nodeAActive = new Semaphore(0);
        final Semaphore nodeACompleted = new Semaphore(0);
        final AtomicReference<String> resultA = new AtomicReference<>();

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                logger.info("Variable changed {} = {}.", event.getVariableId(), event.getNewValue());
                if (event.getVariableId().equals("serviceA-cancelUrl")) {
                    nodeAActive.release();
                }
                if (event.getVariableId().equals("resultA")) {
                    resultA.set(event.getNewValue().toString());
                    nodeACompleted.release();
                }
            }
        };
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("containerId", "mock");
        ProcessInstance processInstance = (ProcessInstance) kieSession.startProcess("service-orchestration-test", parameters);

        //wait for nodeA active
        nodeAActive.acquire();

        final String pid = Long.toString(processInstance.getId());
        executor.execute(() -> {
            logger.info("Signaling cancel for pid: {}.", pid);
            kieSession.signalEvent(Constant.CANCEL_SIGNAL_TYPE, pid);
        });
        nodeACompleted.tryAcquire(8, TimeUnit.SECONDS);
        kieSession.removeEventListener(processEventListener);
        logger.info("Cancelled A result: {}", resultA.get());
        Assert.assertEquals("{canceled=true}", resultA.get());
    }

    @Test @Ignore
    public void testTimeoutServiceDoesNotRespondCancelSucess() throws InterruptedException {
        KieSession kieSession = getRuntimeEngine().getKieSession();

        final Semaphore nodeACompleted = new Semaphore(0);
        final AtomicReference<String> resultA = new AtomicReference<>();

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                if (event.getVariableId().equals("resultA")) {
                    resultA.set(event.getNewValue().toString());
                    nodeACompleted.release();
                }
            }
        };
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<String, Object>();

        parameters.put("containerId", "mock");
        ProcessInstance processInstance = (ProcessInstance) kieSession.startProcess("timeout-test", parameters);

        boolean completed = nodeACompleted.tryAcquire(10, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }
        kieSession.removeEventListener(processEventListener);
        Assert.assertEquals("{canceled=true}", resultA.get());
    }

    /**
     * The test will execute a process with just one task that is set with 2s timeout while the REST service invoked in it is set with 10sec callback time.
     * After 2 seconds the timeout process will kick in by finishing the REST workitem and setting the information that it has failed.
     * 
     * @throws InterruptedException
     */
    @Test @Ignore
    public void testTimeoutServiceDoesNotRespondCancelTimeouts() throws InterruptedException {

        final Semaphore nodeACompleted = new Semaphore(0);
        final AtomicReference<String> resultA = new AtomicReference<>();

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                if (event.getVariableId().equals("resultA")) {
                    resultA.set(event.getNewValue().toString());
                    nodeACompleted.release();
                }
            }
        };
        
        KieSession kieSession = getRuntimeEngine().getKieSession();
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("containerId", "mock");
        ProcessInstance processInstance = (ProcessInstance) kieSession.startProcess("timeout-test-cancel-timeouts", parameters);

        boolean completed = nodeACompleted.tryAcquire(15, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }
        kieSession.removeEventListener(processEventListener);
        Assert.assertEquals("{\"remote-cancel-failed\":true}", resultA.get());
        
        assertProcessInstanceCompleted(processInstance.getId());
        disposeRuntimeManager();
    }

    @Test @Ignore
    public void testInvalidServiceUrl() throws InterruptedException {
        KieSession kieSession = getRuntimeEngine().getKieSession();

        final Semaphore nodeACompleted = new Semaphore(0);
        final AtomicReference<String> resultA = new AtomicReference<>();

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                if (event.getVariableId().equals("resultA")) {
                    resultA.set(event.getNewValue().toString());
                    nodeACompleted.release();
                }
            }
        };
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<String, Object>();

        parameters.put("containerId", "mock");
        ProcessInstance processInstance = (ProcessInstance) kieSession.startProcess("invalid-service-url-test", parameters);

        boolean completed = nodeACompleted.tryAcquire(15, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }
        kieSession.removeEventListener(processEventListener);
        Assert.assertEquals("{\"remote-cancel-failed\":true}", resultA.get());
    }

    @Test @Ignore
    public void processFlowTest() throws InterruptedException {
        KieSession kieSession = getRuntimeEngine().getKieSession();

        final Semaphore nodeACompleted = new Semaphore(0);
        final AtomicReference<Boolean> serviceASuccessCompletion = new AtomicReference<>();
        final Semaphore nodeBCompleted = new Semaphore(0);
        final AtomicReference<String> resultB = new AtomicReference<>();

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                logger.info("Process variable: {}, changed to: {}.", event.getVariableId(), event.getNewValue());
                if (event.getVariableId().equals("serviceA-successCompletion")) {
                    serviceASuccessCompletion.set((Boolean) event.getNewValue());
                    nodeACompleted.release();
                }
                if (event.getVariableId().equals("resultB")) {
                    resultB.set(event.getNewValue().toString());
                }
                if (event.getVariableId().equals("serviceB-successCompletion")) {
                    nodeBCompleted.release();
                }
            }
        };
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("containerId", "mock");
        WorkflowProcessInstance processInstance = (WorkflowProcessInstance) kieSession.startProcess("org.jbpm.ServiceFlowTest", parameters);

        boolean completed = nodeACompleted.tryAcquire(5, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }
        Assert.assertTrue(serviceASuccessCompletion.get());

        nodeBCompleted.tryAcquire(5, TimeUnit.SECONDS);

        Assert.assertEquals("{fullName=Matej Lazar}", resultB.get());

        kieSession.removeEventListener(processEventListener);
    }

    @Test @Ignore
    public void processFlowCancelOnFailureTest() throws InterruptedException {
        KieSession kieSession = getRuntimeEngine().getKieSession();

        final Semaphore nodeACompleted = new Semaphore(0);
        final AtomicReference<Boolean> serviceASuccessCompletion = new AtomicReference<>();
        final Semaphore nodeBCompleted = new Semaphore(0);
        final AtomicReference<String> resultB = new AtomicReference<>();

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                logger.info("Process variable: {}, changed to: {}.", event.getVariableId(), event.getNewValue());
                if (event.getVariableId().equals("serviceA-successCompletion")) {
                    serviceASuccessCompletion.set((Boolean) event.getNewValue());
                    nodeACompleted.release();
                }
                if (event.getVariableId().equals("resultB")) {
                    resultB.set(event.getNewValue().toString());
                }
                if (event.getVariableId().equals("serviceB-successCompletion")) {
                    nodeBCompleted.release();
                }
            }
        };
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("containerId", "mock");
        WorkflowProcessInstance processInstance = (WorkflowProcessInstance) kieSession.startProcess("org.jbpm.ServiceFlowFailingServiceTest", parameters);

        boolean completed = nodeACompleted.tryAcquire(5, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }
        Assert.assertFalse(serviceASuccessCompletion.get());

        nodeBCompleted.tryAcquire(5, TimeUnit.SECONDS);

        Assert.assertEquals(null, resultB.get());

        kieSession.removeEventListener(processEventListener);
    }

    @Test @Ignore
    public void processFlowMustRunAfterTest() throws InterruptedException {
        KieSession kieSession = getRuntimeEngine().getKieSession();

        final Semaphore nodeACompleted = new Semaphore(0);
        final AtomicReference<Boolean> serviceASuccessCompletion = new AtomicReference<>();
        final Semaphore nodeBCompleted = new Semaphore(0);
        final AtomicReference<String> resultTimeout = new AtomicReference<>();
        final AtomicReference<String> resultB = new AtomicReference<>();

        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
                logger.info("Process variable: {}, changed to: {}.", event.getVariableId(), event.getNewValue());
                if (event.getVariableId().equals("serviceA-successCompletion")) {
                    serviceASuccessCompletion.set((Boolean) event.getNewValue());
                    nodeACompleted.release();
                }
                if (event.getVariableId().equals("resultTimeout")) {
                    resultTimeout.set((String) event.getNewValue());
                }
                if (event.getVariableId().equals("resultB")) {
                    resultB.set(event.getNewValue().toString());
                }
                if (event.getVariableId().equals("serviceB-successCompletion")) {
                    nodeBCompleted.release();
                }
            }
        };
        kieSession.addEventListener(processEventListener);

        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("containerId", "mock");
        WorkflowProcessInstance processInstance = (WorkflowProcessInstance) kieSession.startProcess("org.jbpm.ServiceFlowMustRunAfterFailingServiceTest", parameters);

        boolean completed = nodeACompleted.tryAcquire(5, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }
        Assert.assertTrue(serviceASuccessCompletion.get());

        nodeBCompleted.tryAcquire(5, TimeUnit.SECONDS);

        Assert.assertEquals("{fullName=Matej Lazar}", resultB.get());
        Assert.assertEquals("{\"remote-cancel-failed\":true}", resultTimeout.get());

        kieSession.removeEventListener(processEventListener);
    }
    
    @Test @Ignore
    public void testWrappedWorkitem() throws InterruptedException {
        
        KieSession kieSession = getRuntimeEngine().getKieSession();
        
        // Semaphore for process completed event
        final Semaphore processFinished = new Semaphore(0);
        
        ProcessEventListener processEventListener = new DefaultProcessEventListener() {
            
            @Override
            public void beforeNodeTriggered(ProcessNodeTriggeredEvent event) {
                logger.info("Event ID: {}, event node ID: {}, event node name: {}", event.getNodeInstance().getId(), event.getNodeInstance().getNodeId(), event.getNodeInstance().getNodeName());
            }
            
            public void afterProcessCompleted(ProcessCompletedEvent event) {
                logger.info("Process completed, unblocking test.");
                processFinished.release();
            }
            
        };
        kieSession.addEventListener(processEventListener);
        
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("containerId", "mock");
        parameters.put("cancel", false);
        parameters.put("url", "http://localhost:8080/demo-service/service/A?callbackDelay=3");
        parameters.put("template", ""
                + "{\"callbackUrl\":\"${handler.callback.url}\","
                + "\"callbackMethod\":\"POST\","
                + "\"name\":\"Matej\"}");
        parameters.put("method", "POST" );
        parameters.put("taskTimeout", 10);
        parameters.put("cancelTimeout", 10);
        parameters.put("cancelUrlJsonPointer", "/cancelUrl");
        
        ProcessInstance processInstance = (ProcessInstance) kieSession.startProcess("execute-rest", parameters);

        boolean completed = processFinished.tryAcquire(30, TimeUnit.SECONDS);
        if (!completed) {
            Assert.fail("Failed to complete the process.");
        }
        
        kieSession.removeEventListener(processEventListener);
        assertProcessInstanceCompleted(processInstance.getId());
        
    }

    @Test @Ignore
    public void testWorkitemValidity() {

        String repoPath = "file://" + System.getProperty("builddir") +
                "/" + System.getProperty("artifactId") + "-" +
                System.getProperty("version") + "/";

        Map<String, WorkDefinitionImpl> repoResults = new WorkItemRepository().getWorkDefinitions(repoPath,
                                                                                                  null,
                                                                                                  System.getProperty("artifactId"));
        assertNotNull(repoResults);
        assertEquals(1,
                     repoResults.size());
    }
}