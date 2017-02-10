/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * SessionDelegate
 *
 * @author Rafael H. Schloming
 */

public class SessionDelegate
    extends MethodDelegate<Session>
    implements ProtocolDelegate<Session>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionDelegate.class);

    public void init(Session ssn, ProtocolHeader hdr)
    {
        LOGGER.warn("INIT: [{}] {}", ssn, hdr);
    }

    public void control(Session ssn, Method method)
    {
        method.dispatch(ssn, this);
    }

    public void command(Session ssn, Method method)
    {
        command(ssn, method, !method.hasPayload());
    }
    public void command(Session ssn, Method method, boolean processed) 
    {
        ssn.identify(method);
        method.dispatch(ssn, this);
        if (processed)
        {
            ssn.processed(method);
        }
    }

    public void error(Session ssn, ProtocolError error)
    {
        LOGGER.warn("ERROR: [{}] {}", ssn, error);
    }

    public void handle(Session ssn, Method method)
    {
        LOGGER.warn("UNHANDLED: [{}] {}", ssn, method);
    }

    @Override public void sessionRequestTimeout(Session ssn, SessionRequestTimeout t)
    {
        if (t.getTimeout() == 0)
        {
            ssn.setClose(true);
        }
        ssn.sessionTimeout(0); // Always report back an expiry of 0 until it is implemented
    }

    @Override public void sessionAttached(Session ssn, SessionAttached atc)
    {
        ssn.setState(Session.State.OPEN);
        synchronized (ssn.getStateLock())
        {
            ssn.getStateLock().notifyAll();
        }
    }

    @Override public void sessionTimeout(Session ssn, SessionTimeout t)
    {
        // Setting of expiry is not implemented
    }

    @Override public void sessionCompleted(Session ssn, SessionCompleted cmp)
    {
        RangeSet ranges = cmp.getCommands();
        RangeSet known = null;

        if (ranges != null)
        {
            if(ranges.size() == 1)
            {
                Range range = ranges.getFirst();
                boolean advanced = ssn.complete(range.getLower(), range.getUpper());

                if(advanced && cmp.getTimelyReply())
                {
                    known = range;
                }
            }
            else
            {
                if (cmp.getTimelyReply())
                {
                    known = RangeSetFactory.createRangeSet();
                }
                for (Range range : ranges)
                {
                    boolean advanced = ssn.complete(range.getLower(), range.getUpper());
                    if (advanced && known != null)
                    {
                        known.add(range);
                    }
                }
            }
        }
        else if (cmp.getTimelyReply())
        {
            known = RangeSetFactory.createRangeSet();
        }

        if (known != null)
        {
            ssn.sessionKnownCompleted(known);
        }
    }

    @Override public void sessionKnownCompleted(Session ssn, SessionKnownCompleted kcmp)
    {
        RangeSet kc = kcmp.getCommands();
        if (kc != null)
        {
            ssn.knownComplete(kc);
        }
    }

    @Override public void sessionFlush(Session ssn, SessionFlush flush)
    {
        if (flush.getCompleted())
        {
            ssn.flushProcessed();
        }
        if (flush.getConfirmed())
        {
           ssn.flushProcessed();
        }
        if (flush.getExpected())
        {
            ssn.flushExpected();
        }
    }

    @Override public void sessionCommandPoint(Session ssn, SessionCommandPoint scp)
    {
        ssn.commandPoint(scp.getCommandId());
    }

    @Override public void executionSync(Session ssn, ExecutionSync sync)
    {
        ssn.syncPoint();
    }

    @Override public void executionResult(Session ssn, ExecutionResult result)
    {
        ssn.result(result.getCommandId(), result.getValue());
    }

    @Override public void executionException(Session ssn, ExecutionException exc)
    {
        ssn.setException(exc);
        ssn.getSessionListener().exception(ssn, new SessionException(exc));
        ssn.closed();
    }

    @Override public void messageTransfer(Session ssn, MessageTransfer xfr)
    {
        ssn.getSessionListener().message(ssn, xfr);
    }

    @Override public void messageSetFlowMode(Session ssn, MessageSetFlowMode sfm)
    {
        if ("".equals(sfm.getDestination()) &&
            MessageFlowMode.CREDIT.equals(sfm.getFlowMode()))
        {
            ssn.setFlowControl(true);
        }
        else
        {
            super.messageSetFlowMode(ssn, sfm);
        }
    }

    @Override public void messageFlow(Session ssn, MessageFlow flow)
    {
        if ("".equals(flow.getDestination()) &&
            MessageCreditUnit.MESSAGE.equals(flow.getUnit()))
        {
            ssn.addCredit((int) flow.getValue());
        }
        else
        {
            super.messageFlow(ssn, flow);
        }
    }

    @Override public void messageStop(Session ssn, MessageStop stop)
    {
        if ("".equals(stop.getDestination()))
        {
            ssn.drainCredit();
        }
        else
        {
            super.messageStop(ssn, stop);
        }
    }

    public void closed(Session session)
    {
        LOGGER.debug("CLOSED: [{}]", session);
        synchronized (session.getStateLock())
        {
            session.getStateLock().notifyAll();
        }
    }

    public void detached(Session session)
    {
        LOGGER.debug("DETACHED: [{}]", session);
        synchronized (session.getStateLock())
        {
            session.getStateLock().notifyAll();
        }
    }
}