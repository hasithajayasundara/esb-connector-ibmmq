/*
*  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.esb.connector;

import com.ibm.mq.*;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQMD;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;

import java.io.IOException;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ibm.mq.constants.CMQC.*;

/**
 * Add messages to queue
 */
public class MQPublishQueue extends AbstractConnector {

    MQConnectionBuilder connectionBuilder;
    MQConfiguration config;
    MQQueue queue = null;

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        try {

            log.info("Message received at ibm-mq connector");
            connectionBuilder = new MQConnectionBuilder(messageContext);
            config = connectionBuilder.getConfig();

            SOAPEnvelope soapEnvelope = messageContext.getEnvelope();
            OMElement getElement = soapEnvelope.getBody().getFirstElement();
            String queueMessage = "";

            if (getElement != null) {
                queueMessage = getElement.toString();
            }

            int messageType = config.getMessageType();
            MQMessage mqMessage;

            if (config.getQueue() != null) {
                queue = setQueue(connectionBuilder, config);
            }

            switch (messageType) {
                case 1:
                    mqMessage = buildRequestMessageandGetResponse(config, queueMessage);
                    break;
                case 4:
                    mqMessage = buildReportMessage(config, queueMessage);
                    break;
                default:
                    mqMessage = buildDatagramOrReplyMessage(config, queueMessage);
            }

            if (queue == null || mqMessage == null) {
                log.error("Error in publishing message");
            } else {
                log.info("queue initialized");
                queue.put(mqMessage);
                log.info("Message successfully placed at " + config.getQueue() + "queue");
            }

            connectionBuilder.closeConnection();

        } catch (Exception e) {
            log.error("Problem in publishing to message" + e);
            throw new ConnectException(e);
        }
    }

    /**
     * Initialize queue
     */
    MQQueue setQueue(MQConnectionBuilder connectionBuilder, MQConfiguration config) {
        MQQueueManager manager = connectionBuilder.getQueueManager();
        MQQueue queue;
        try {
            queue = manager.accessQueue(config.getQueue(), CMQC.MQOO_OUTPUT);
        } catch (MQException e) {
            log.error("Error creating queue " + e);
            return null;
        }
        return queue;
    }

    /**
     * Create datagram or request MQMessage
     */
    MQMessage buildDatagramOrReplyMessage(MQConfiguration config, String queueMessage) {

        MQMessage mqMessage = new MQMessage();
        mqMessage.messageId = config.getMessageID().getBytes();
        mqMessage.correlationId = config.getCorrelationID().getBytes();
        if (config.getPriority() != -1000) {
            mqMessage.priority = config.getPriority();
        }

        int messageType = config.getMessageType();
        mqMessage.messageType = messageType;

        if (config.isPersistent()) {
            mqMessage.persistence = MQPER_PERSISTENT;
        } else {
            mqMessage.persistence = MQPER_NOT_PERSISTENT;
        }
        if (config.getgroupID() != null) {
            mqMessage.groupId = config.getgroupID().getBytes();
        }

        GregorianCalendar cal = new GregorianCalendar();
        cal.setTime(new Date(System.currentTimeMillis()));
        mqMessage.putDateTime = cal;

        try {
            mqMessage.writeString(queueMessage);
        } catch (Exception e) {
            log.info("Error creating mq message" + e);
        }
        return mqMessage;
    }

    /**
     * Create request MQMesaage
     */
    MQMessage buildRequestMessageandGetResponse(MQConfiguration config, String queueMessage) {

        MQMessage mqMessage = new MQMessage();
        mqMessage.messageId = config.getMessageID().getBytes();
        mqMessage.correlationId = config.getCorrelationID().getBytes();
        if (config.getPriority() != -1000) {
            mqMessage.priority = config.getPriority();
        }
        int messageType = config.getMessageType();
        mqMessage.messageType = messageType;
        String reportQueue = config.getreplyQueue();
        if (reportQueue != null) {
            MQQueue reply;
            reply = setReplyQueue(this.connectionBuilder, this.config);
            if (reply != null) {
                getReplyMessge(reply, this.config);
            }
        } else {
            log.info("Reply queue not specified");
        }
        if (config.isPersistent()) {
            mqMessage.persistence = MQPER_PERSISTENT;
        } else {
            mqMessage.persistence = MQPER_NOT_PERSISTENT;
        }
        if (config.getgroupID() != null) {
            mqMessage.groupId = config.getgroupID().getBytes();
        }

        GregorianCalendar cal = new GregorianCalendar();
        cal.setTime(new Date(System.currentTimeMillis()));
        mqMessage.putDateTime = cal;
        try {
            mqMessage.writeString(queueMessage);
        } catch (Exception e) {
            log.info("Error creating mq message" + e);
        }
        return mqMessage;
    }

    /**
     * Create report MQMesaage
     */
    MQMessage buildReportMessage(MQConfiguration config, String queueMessage) {

        MQMessage mqMessage = new MQMessage();
        mqMessage.messageId = config.getMessageID().getBytes();
        mqMessage.correlationId = config.getCorrelationID().getBytes();
        if (config.getPriority() != -1000) {
            mqMessage.priority = config.getPriority();
        }

        int messageType = config.getMessageType();
        mqMessage.messageType = messageType;
        String reportQueue = config.getreplyQueue();
        if (reportQueue != null) {
            mqMessage.replyToQueueName = reportQueue;
            mqMessage.report = CMQC.MQRO_COA_WITH_FULL_DATA | CMQC.MQRO_EXCEPTION_WITH_FULL_DATA | CMQC.MQRO_COD_WITH_FULL_DATA | CMQC.MQRO_EXPIRATION_WITH_FULL_DATA;
            MQQueue reply;
            reply = setReplyQueue(this.connectionBuilder, this.config);
            if (reply != null) {
                getReportMessage(reply, this.config);
            }
        } else {
            log.info("Reply queue not specified");
        }

        if (config.isPersistent()) {
            mqMessage.persistence = MQPER_PERSISTENT;
        } else {
            mqMessage.persistence = MQPER_NOT_PERSISTENT;
        }
        if (config.getgroupID() != null) {
            mqMessage.groupId = config.getgroupID().getBytes();
        }

        GregorianCalendar cal = new GregorianCalendar();
        cal.setTime(new Date(System.currentTimeMillis()));
        mqMessage.putDateTime = cal;
        try {
            mqMessage.writeString(queueMessage);
        } catch (Exception e) {
            log.info("Error creating mq message" + e);
        }
        return mqMessage;
    }

    /**
     * Get report message
     */
    void getReportMessage(final MQQueue replyQueue, MQConfiguration config) {

        final MQMessage message = new MQMessage();
        final MQGetMessageOptions gmo = new MQGetMessageOptions();
        message.messageId = config.getMessageID().getBytes();
        message.correlationId = config.getCorrelationID().getBytes();
        gmo.matchOptions = MQConstants.MQMO_MATCH_CORREL_ID;
        gmo.matchOptions = MQConstants.MQMO_MATCH_GROUP_ID;

        ExecutorService executorService = Executors.newSingleThreadExecutor();

        executorService.execute(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        replyQueue.get(message, gmo);
                        MQMD md = new MQMD();
                        md.copyFrom(message);
                        log.info("Report" + reportStr(md.getFeedback()));
                        break;
                    } catch (MQException e) {
                        log.info("Waiting for reply message");
                    }
                }
            }
        });
        executorService.shutdown();
    }

    /**
     * Get reply message
     */
    void getReplyMessge(final MQQueue replyQueue, MQConfiguration config) {

        final MQMessage message = new MQMessage();
        final MQGetMessageOptions gmo = new MQGetMessageOptions();
        message.messageId = config.getMessageID().getBytes();
        message.correlationId = config.getCorrelationID().getBytes();
        gmo.matchOptions = MQConstants.MQMO_MATCH_CORREL_ID;
        gmo.matchOptions = MQConstants.MQMO_MATCH_GROUP_ID;

        Timer replyMessage = new Timer();

        replyMessage.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    replyQueue.get(message, gmo);
                    log.info("Reply received");
                    MQMD md = new MQMD();
                    md.copyFrom(message);
                    message.getDataLength();
                    int strLen = message.getDataLength();
                    byte[] strData = new byte[strLen];
                    message.readFully(strData);
                    log.info("Reply-" + new String(strData));
                } catch (MQException e) {

                } catch (IOException e) {
                }
            }
        }, 0, config.getReplyTimeout() * 1000);
    }

    /**
     * generate report details
     */
    String reportStr(int code) {
        switch (code) {
            case MQFB_EXPIRATION:
                return MQReportConstants.MQFB_EXPIRATION;
            case MQFB_COA:
                return MQReportConstants.MQFB_COA;
            case MQFB_COD:
                return MQReportConstants.MQFB_COD;
            case MQFB_QUIT:
                return MQReportConstants.MQFB_QUIT;
            case MQFB_APPL_CANNOT_BE_STARTED:
                return MQReportConstants.MQFB_APPL_CANNOT_BE_STARTED;
            case MQFB_TM_ERROR:
                return MQReportConstants.MQFB_TM_ERROR;
            case MQFB_APPL_TYPE_ERROR:
                return MQReportConstants.MQFB_APPL_TYPE_ERROR;
            case MQFB_STOPPED_BY_MSG_EXIT:
                return MQReportConstants.MQFB_STOPPED_BY_MSG_EXIT;
            case MQFB_XMIT_Q_MSG_ERROR:
                return MQReportConstants.MQFB_XMIT_Q_MSG_ERROR;
            case MQFB_ACTIVITY:
                return MQReportConstants.MQFB_ACTIVITY;
            case MQFB_MAX_ACTIVITIES:
                return MQReportConstants.MQFB_MAX_ACTIVITIES;
            case MQFB_NOT_FORWARDED:
                return MQReportConstants.MQFB_NOT_FORWARDED;
            case MQFB_NOT_DELIVERED:
                return MQReportConstants.MQFB_NOT_DELIVERED;
            case MQFB_UNSUPPORTED_FORWARDING:
                return MQReportConstants.MQFB_UNSUPPORTED_FORWARDING;
            case MQFB_UNSUPPORTED_DELIVERY:
                return MQReportConstants.MQFB_UNSUPPORTED_DELIVERY;
            default:
                return MQReportConstants.MQFB_NONE;
        }
    }

    /**
     * Initialize reply queue
     */
    MQQueue setReplyQueue(MQConnectionBuilder connectionBuilder, MQConfiguration config) {
        MQQueueManager manager = connectionBuilder.getQueueManager();
        MQQueue queue;
        try {
            queue = manager.accessQueue(config.getreplyQueue(), CMQC.MQRC_READ_AHEAD_MSGS);
        } catch (MQException e) {
            log.error("Error creating reply queue" + e);
            return null;
        }
        return queue;
    }
}