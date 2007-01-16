/*
 * Copyright 2006 The Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *  
 */

package org.apache.sandesha2.msgprocessors;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.xml.namespace.QName;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.engine.AxisEngine;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sandesha2.RMMsgContext;
import org.apache.sandesha2.Sandesha2Constants;
import org.apache.sandesha2.SandeshaException;
import org.apache.sandesha2.i18n.SandeshaMessageHelper;
import org.apache.sandesha2.i18n.SandeshaMessageKeys;
import org.apache.sandesha2.security.SecurityManager;
import org.apache.sandesha2.security.SecurityToken;
import org.apache.sandesha2.storage.StorageManager;
import org.apache.sandesha2.storage.beanmanagers.InvokerBeanMgr;
import org.apache.sandesha2.storage.beanmanagers.RMDBeanMgr;
import org.apache.sandesha2.storage.beanmanagers.SenderBeanMgr;
import org.apache.sandesha2.storage.beans.InvokerBean;
import org.apache.sandesha2.storage.beans.RMDBean;
import org.apache.sandesha2.storage.beans.SenderBean;
import org.apache.sandesha2.util.AcknowledgementManager;
import org.apache.sandesha2.util.FaultManager;
import org.apache.sandesha2.util.MsgInitializer;
import org.apache.sandesha2.util.SandeshaUtil;
import org.apache.sandesha2.wsrm.Sequence;

/**
 * Responsible for processing the Sequence header (if present) on an incoming
 * message.
 */

public class SequenceProcessor {

	private static final Log log = LogFactory.getLog(SequenceProcessor.class);

	public boolean processSequenceHeader(RMMsgContext rmMsgCtx) throws AxisFault {
		if (log.isDebugEnabled())
			log.debug("Enter: SequenceProcessor::processSequenceHeader");
		boolean result = false;
		Sequence sequence = (Sequence) rmMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.SEQUENCE);
		if(sequence != null) {
			// This is a reliable message, so hand it on to the main routine
			result = processReliableMessage(rmMsgCtx);
		} else {
			if (log.isDebugEnabled())
				log.debug("Message does not contain a sequence header");
		}
		if (log.isDebugEnabled())
			log.debug("Exit: SequenceProcessor::processSequenceHeader " + result);
		return result;
	}
	
	public boolean processReliableMessage(RMMsgContext rmMsgCtx) throws AxisFault {
		if (log.isDebugEnabled())
			log.debug("Enter: SequenceProcessor::processReliableMessage");

		boolean msgCtxPaused = false;
		
		if (rmMsgCtx.getProperty(Sandesha2Constants.APPLICATION_PROCESSING_DONE) != null
				&& rmMsgCtx.getProperty(Sandesha2Constants.APPLICATION_PROCESSING_DONE).equals("true")) {
			return msgCtxPaused;
		}

		MessageContext msgCtx = rmMsgCtx.getMessageContext();
		StorageManager storageManager = SandeshaUtil.getSandeshaStorageManager(msgCtx.getConfigurationContext(),msgCtx.getConfigurationContext().getAxisConfiguration());
		Sequence sequence = (Sequence) rmMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.SEQUENCE);
		String sequenceId = sequence.getIdentifier().getIdentifier();
		
		// Check that both the Sequence header and message body have been secured properly
		RMDBeanMgr mgr = storageManager.getRMDBeanMgr();
		RMDBean bean = mgr.retrieve(sequenceId);
		
		if(bean != null && bean.getSecurityTokenData() != null) {
			SecurityManager secManager = SandeshaUtil.getSecurityManager(msgCtx.getConfigurationContext());
			
			QName seqName = new QName(rmMsgCtx.getRMNamespaceValue(), Sandesha2Constants.WSRM_COMMON.SEQUENCE);
			
			SOAPEnvelope envelope = msgCtx.getEnvelope();
			OMElement body = envelope.getBody();
			OMElement seqHeader = envelope.getHeader().getFirstChildWithName(seqName);
			
			SecurityToken token = secManager.recoverSecurityToken(bean.getSecurityTokenData());
			
			secManager.checkProofOfPossession(token, seqHeader, msgCtx);
			secManager.checkProofOfPossession(token, body, msgCtx);
		}
		
		// setting acked msg no range
		ConfigurationContext configCtx = rmMsgCtx.getMessageContext().getConfigurationContext();
		if (configCtx == null) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.configContextNotSet);
			log.debug(message);
			throw new SandeshaException(message);
		}

		FaultManager.checkForUnknownSequence(rmMsgCtx, sequenceId, storageManager);

		// setting mustUnderstand to false.
		sequence.setMustUnderstand(false);
		rmMsgCtx.addSOAPEnvelope();

		if (bean == null) {
			throw new SandeshaException(SandeshaMessageHelper.getMessage(SandeshaMessageKeys.cannotFindSequence,
					sequenceId));
		}

		// throwing a fault if the sequence is closed.
		FaultManager.checkForSequenceClosed(rmMsgCtx, sequenceId, bean);
		FaultManager.checkForLastMsgNumberExceeded(rmMsgCtx, storageManager);

		long msgNo = sequence.getMessageNumber().getMessageNumber();
		if (msgNo == 0) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.invalidMsgNumber, Long
					.toString(msgNo));
			log.debug(message);
			throw new SandeshaException(message);
		}

		// Pause the messages bean if not the right message to invoke.
		
		// updating the last activated time of the sequence.
		bean.setLastActivatedTime(System.currentTimeMillis());
		
		String key = SandeshaUtil.getUUID(); // key to store the message.
		// updating the Highest_In_Msg_No property which gives the highest
		// message number retrieved from this sequence.
		long highestInMsgNo = bean.getHighestInMessageNumber();

		if (msgNo > highestInMsgNo) {
			// If WS-Addressing is turned off there may not be a message id written into the SOAP
			// headers, but we can still fake one up to help us match up requests and replies within
			// this end of the connection.
			String messageId = msgCtx.getMessageID();
			if(messageId == null) {
				messageId = SandeshaUtil.getUUID();
				msgCtx.setMessageID(messageId);
			}
			
			bean.setHighestInMessageId(messageId);
			bean.setHighestInMessageNumber(msgNo);
		}

		// Get the server completed messages list
		List serverCompletedMessages = bean.getServerCompletedMessages();
		
		// If the message in the list of completed
		boolean msgNoPresentInList = serverCompletedMessages.contains(new Long(msgNo));
		
		if (msgNoPresentInList
				&& (Sandesha2Constants.QOS.InvocationType.DEFAULT_INVOCATION_TYPE == Sandesha2Constants.QOS.InvocationType.EXACTLY_ONCE)) {
			// this is a duplicate message and the invocation type is
			// EXACTLY_ONCE.
			rmMsgCtx.pause();
			msgCtxPaused = true;
		}

		if (!msgNoPresentInList)
		{
			serverCompletedMessages.add(new Long(msgNo));
		}
		
		// Update the RMD bean
		mgr.update(bean);

		// inorder invocation is still a global property
		boolean inOrderInvocation = SandeshaUtil.getPropertyBean(
				msgCtx.getConfigurationContext().getAxisConfiguration()).isInOrder();


		//setting properties for the messageContext
		rmMsgCtx.setProperty(Sandesha2Constants.MessageContextProperties.SEQUENCE_ID,sequenceId);
		rmMsgCtx.setProperty(Sandesha2Constants.MessageContextProperties.MESSAGE_NUMBER,new Long (msgNo));
		
		if (inOrderInvocation && !msgNoPresentInList) {

			InvokerBeanMgr storageMapMgr = storageManager.getInvokerBeanMgr();

			// saving the message.
			try {
				storageManager.storeMessageContext(key, rmMsgCtx.getMessageContext());
				storageMapMgr.insert(new InvokerBean(key, msgNo, sequenceId));

				// This will avoid performing application processing more
				// than
				// once.
				rmMsgCtx.setProperty(Sandesha2Constants.APPLICATION_PROCESSING_DONE, "true");

			} catch (Exception ex) {
				throw new SandeshaException(ex.getMessage(), ex);
			}

			// pause the message
			rmMsgCtx.pause();
			msgCtxPaused = true;

			// Starting the invoker if stopped.
			SandeshaUtil.startInvokerForTheSequence(msgCtx.getConfigurationContext(), sequenceId);

		}

		// Sending acknowledgements
		sendAckIfNeeded(rmMsgCtx, storageManager);

		if (log.isDebugEnabled())
			log.debug("Exit: SequenceProcessor::processReliableMessage " + msgCtxPaused);
		return msgCtxPaused;
	}

	public static void sendAckIfNeeded(RMMsgContext rmMsgCtx, StorageManager storageManager)
			throws AxisFault {

		if (log.isDebugEnabled())
			log.debug("Enter: SequenceProcessor::sendAckIfNeeded");

		String sequencePropertyKey = SandeshaUtil.getSequencePropertyKey(rmMsgCtx);
		
		Sequence sequence = (Sequence) rmMsgCtx.getMessagePart(Sandesha2Constants.MessageParts.SEQUENCE);
		String sequenceId = sequence.getIdentifier().getIdentifier();
		ConfigurationContext configCtx = rmMsgCtx.getMessageContext().getConfigurationContext();
		if (configCtx == null) {
			String message = SandeshaMessageHelper.getMessage(SandeshaMessageKeys.configContextNotSet);
			if(log.isDebugEnabled()) log.debug(message);
			throw new SandeshaException(message);
		}
		
		RMMsgContext ackRMMsgCtx = AcknowledgementManager.generateAckMessage(rmMsgCtx, sequencePropertyKey, sequenceId, storageManager);
		MessageContext ackMsgCtx = ackRMMsgCtx.getMessageContext();
		
		EndpointReference ackTo = ackRMMsgCtx.getTo();
		EndpointReference replyTo = rmMsgCtx.getReplyTo();
		boolean anonAck = ackTo == null || ackTo.hasAnonymousAddress();
		boolean anonReply = replyTo == null || replyTo.hasAnonymousAddress();

		// Only use the backchannel for ack messages if we are sure that the application
		// doesn't need it. A 1-way MEP should be complete by now.
		boolean complete = ackMsgCtx.getOperationContext().isComplete();
		if (anonAck && anonReply && !complete) {
			if (log.isDebugEnabled()) log.debug("Exit: SequenceProcessor::sendAckIfNeeded, avoiding using backchannel");
			return;
		}
			
		if(anonAck) {
			// setting CONTEXT_WRITTEN since acksto is anonymous
			if (rmMsgCtx.getMessageContext().getOperationContext() == null) {
				// operation context will be null when doing in a GLOBAL
				// handler.
				AxisOperation op = ackMsgCtx.getAxisOperation();
				OperationContext opCtx = new OperationContext(op);
				rmMsgCtx.getMessageContext().setOperationContext(opCtx);
			}

			rmMsgCtx.getMessageContext().getOperationContext().setProperty(
					org.apache.axis2.Constants.RESPONSE_WRITTEN, Constants.VALUE_TRUE);

			rmMsgCtx.getMessageContext().setProperty(Sandesha2Constants.ACK_WRITTEN, "true");

			ackRMMsgCtx.getMessageContext().setServerSide(true);
			
			AxisEngine engine = new AxisEngine(configCtx);
			engine.send(ackRMMsgCtx.getMessageContext());

		} else if(!anonAck) {

			// / Transaction asyncAckTransaction =
			// storageManager.getTransaction();

			SenderBeanMgr retransmitterBeanMgr = storageManager.getSenderBeanMgr();

			String key = SandeshaUtil.getUUID();

			SenderBean ackBean = new SenderBean();
			ackBean.setMessageContextRefKey(key);
			ackBean.setMessageID(ackMsgCtx.getMessageID());
			ackBean.setReSend(false);
			ackBean.setSequenceID(sequencePropertyKey);
			ackBean.setInternalSequenceID(sequencePropertyKey);
			EndpointReference to = ackMsgCtx.getTo();
			if (to!=null)
				ackBean.setToAddress(to.getAddress());

			// this will be set to true in the sender.
			ackBean.setSend(true);

			ackMsgCtx.setProperty(Sandesha2Constants.QUALIFIED_FOR_SENDING, Sandesha2Constants.VALUE_FALSE);

			ackBean.setMessageType(Sandesha2Constants.MessageTypes.ACK);
			long ackInterval = SandeshaUtil.getPropertyBean(rmMsgCtx.getMessageContext().getAxisService())
					.getAcknowledgementInterval();

			// Ack will be sent as stand alone, only after the retransmitter
			// interval.
			long timeToSend = System.currentTimeMillis() + ackInterval;

			// removing old acks.
			SenderBean findBean = new SenderBean();
			findBean.setMessageType(Sandesha2Constants.MessageTypes.ACK);

			// this will be set to true in the sandesha2TransportSender.
			findBean.setSend(true);
			findBean.setReSend(false);
			Collection coll = retransmitterBeanMgr.find(findBean, false);
			Iterator it = coll.iterator();

			if (it.hasNext()) {
				SenderBean oldAckBean = (SenderBean) it.next();
				timeToSend = oldAckBean.getTimeToSend(); // If there is an
															// old ack. This ack
															// will be sent in
															// the old
															// timeToSend.

				// removing the retransmitted entry for the oldAck
				retransmitterBeanMgr.delete(oldAckBean.getMessageID());

				// removing the message store entry for the old ack
				storageManager.removeMessageContext(oldAckBean.getMessageContextRefKey());
			}

			ackBean.setTimeToSend(timeToSend);

			ackMsgCtx.setProperty(Sandesha2Constants.QUALIFIED_FOR_SENDING, Sandesha2Constants.VALUE_FALSE);
			
			// / asyncAckTransaction.commit();

			// passing the message through sandesha2sender
			ackMsgCtx.setProperty(Sandesha2Constants.SET_SEND_TO_TRUE, Sandesha2Constants.VALUE_TRUE);
			ackRMMsgCtx = MsgInitializer.initializeMessage(ackMsgCtx);
			
			SandeshaUtil.executeAndStore(ackRMMsgCtx, key);

			// inserting the new ack.
			retransmitterBeanMgr.insert(ackBean);

			SandeshaUtil.startSenderForTheSequence(ackRMMsgCtx.getConfigurationContext(), sequenceId);
		}
		
		
		if (log.isDebugEnabled())
			log.debug("Exit: SequenceProcessor::sendAckIfNeeded");
	}
}
