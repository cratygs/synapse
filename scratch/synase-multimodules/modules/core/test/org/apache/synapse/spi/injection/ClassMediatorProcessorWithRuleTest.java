package org.apache.synapse.spi.injection;

import junit.framework.TestCase;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.om.OMElement;
import org.apache.synapse.SynapseEnvironment;
import org.apache.synapse.SynapseMessage;
import org.apache.synapse.Processor;
import org.apache.synapse.processors.mediatortypes.ClassMediatorProcessor;
import org.apache.synapse.xml.ClassMediatorProcessorConfigurator;
import org.apache.synapse.axis2.Axis2SynapseMessage;
import org.apache.synapse.axis2.Axis2SynapseEnvironment;
import org.apache.synapse.util.Axis2EnvSetup;
/*
* Copyright 2004,2005 The Apache Software Foundation.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/

public class ClassMediatorProcessorWithRuleTest extends TestCase {
    private MessageContext msgCtx;
    private SynapseEnvironment env;
    private OMElement config;
    private String synapsexml =
            "<synapse xmlns=\"http://ws.apache.org/ns/synapse\">\n" +
                    "<stage name=\"loger\">\n" +
                    "    <classmediator name=\"mediation\" class=\"org.apache.synapse.mediators.LoggerTestSample\"/>\n" +
                    "</stage>\n" +
                    "</synapse>";

    public void setUp() throws Exception {
        msgCtx = Axis2EnvSetup.axis2Deployment("target/synapse-repository");
        config = Axis2EnvSetup.getSynapseConfigElement(synapsexml);
        env = new Axis2SynapseEnvironment(config,
                Thread.currentThread().getContextClassLoader());
    }

    public void testClassMediatorProcessor() throws Exception {

        SynapseMessage smc = new Axis2SynapseMessage(msgCtx);
        env.injectMessage(smc);
        assertNotNull(env.lookupProcessor("mediation"));
    }
    public void testClassMediatorConfigurator() throws Exception {
        ClassMediatorProcessorConfigurator conf = new ClassMediatorProcessorConfigurator();
        Processor pro = conf.createProcessor(env, config.getFirstElement().getFirstElement());
        assertTrue(pro instanceof ClassMediatorProcessor);
        assertEquals("mediation",pro.getName());
    }
}
