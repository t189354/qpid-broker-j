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

/*
 * This file is auto-generated by Qpid Gentools v.0.1 - do not modify.
 * Supported AMQP version:
 *   8-0
 */

package org.apache.qpid.server.protocol.v0_8.transport;

import org.apache.qpid.server.QpidException;
import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.protocol.v0_8.AMQFrameDecodingException;
import org.apache.qpid.server.protocol.v0_8.AMQShortString;
import org.apache.qpid.server.protocol.v0_8.EncodingUtils;
import org.apache.qpid.server.protocol.v0_8.FieldTable;

public class QueueDeclareBody extends AMQMethodBodyImpl implements EncodableAMQDataBlock, AMQMethodBody
{

    public static final int CLASS_ID =  50;
    public static final int METHOD_ID = 10;

    // Fields declared in specification
    private final int _ticket; // [ticket]
    private final AMQShortString _queue; // [queue]
    private final byte _bitfield0; // [passive, durable, exclusive, autoDelete, nowait]
    private final FieldTable _arguments; // [arguments]

    public QueueDeclareBody(
            int ticket,
            AMQShortString queue,
            boolean passive,
            boolean durable,
            boolean exclusive,
            boolean autoDelete,
            boolean nowait,
            FieldTable arguments
                           )
    {
        _ticket = ticket;
        _queue = queue;
        byte bitfield0 = (byte)0;
        if( passive )
        {
            bitfield0 = (byte) (((int) bitfield0) | (1 << 0));
        }

        if( durable )
        {
            bitfield0 = (byte) (((int) bitfield0) | (1 << 1));
        }

        if( exclusive )
        {
            bitfield0 = (byte) (((int) bitfield0) | (1 << 2));
        }

        if( autoDelete )
        {
            bitfield0 = (byte) (((int) bitfield0) | (1 << 3));
        }

        if( nowait )
        {
            bitfield0 = (byte) (((int) bitfield0) | (1 << 4));
        }

        _bitfield0 = bitfield0;
        _arguments = arguments;
    }

    @Override
    public int getClazz()
    {
        return CLASS_ID;
    }

    @Override
    public int getMethod()
    {
        return METHOD_ID;
    }

    public final int getTicket()
    {
        return _ticket;
    }
    public final AMQShortString getQueue()
    {
        return _queue;
    }
    public final boolean getPassive()
    {
        return (((int)(_bitfield0)) & ( 1 << 0)) != 0;
    }
    public final boolean getDurable()
    {
        return (((int)(_bitfield0)) & ( 1 << 1)) != 0;
    }
    public final boolean getExclusive()
    {
        return (((int)(_bitfield0)) & ( 1 << 2)) != 0;
    }
    public final boolean getAutoDelete()
    {
        return (((int)(_bitfield0)) & ( 1 << 3)) != 0;
    }
    public final boolean getNowait()
    {
        return (((int)(_bitfield0)) & ( 1 << 4)) != 0;
    }
    public final FieldTable getArguments()
    {
        return _arguments;
    }

    @Override
    protected int getBodySize()
    {
        int size = 3;
        size += getSizeOf( _queue );
        size += getSizeOf( _arguments );
        return size;
    }

    @Override
    public void writeMethodPayload(QpidByteBuffer buffer)
    {
        writeUnsignedShort( buffer, _ticket );
        writeAMQShortString( buffer, _queue );
        writeBitfield( buffer, _bitfield0 );
        writeFieldTable( buffer, _arguments );
    }

    @Override
    public boolean execute(MethodDispatcher dispatcher, int channelId) throws QpidException
	{
        return dispatcher.dispatchQueueDeclare(this, channelId);
	}

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder("[QueueDeclareBodyImpl: ");
        buf.append( "ticket=" );
        buf.append(  getTicket() );
        buf.append( ", " );
        buf.append( "queue=" );
        buf.append(  getQueue() );
        buf.append( ", " );
        buf.append( "passive=" );
        buf.append(  getPassive() );
        buf.append( ", " );
        buf.append( "durable=" );
        buf.append(  getDurable() );
        buf.append( ", " );
        buf.append( "exclusive=" );
        buf.append(  getExclusive() );
        buf.append( ", " );
        buf.append( "autoDelete=" );
        buf.append(  getAutoDelete() );
        buf.append( ", " );
        buf.append( "nowait=" );
        buf.append(  getNowait() );
        buf.append( ", " );
        buf.append( "arguments=" );
        buf.append(  getArguments() );
        buf.append("]");
        return buf.toString();
    }

    public static void process(final QpidByteBuffer buffer,
                               final ServerChannelMethodProcessor dispatcher) throws AMQFrameDecodingException
    {

        int ticket = buffer.getUnsignedShort();
        AMQShortString queue = AMQShortString.readAMQShortString(buffer);
        byte bitfield = buffer.get();

        boolean passive = (bitfield & 0x01 ) == 0x01;
        boolean durable = (bitfield & 0x02 ) == 0x02;
        boolean exclusive = (bitfield & 0x04 ) == 0x04;
        boolean autoDelete = (bitfield & 0x08 ) == 0x08;
        boolean nowait = (bitfield & 0x010 ) == 0x010;
        FieldTable arguments = EncodingUtils.readFieldTable(buffer);
        if(!dispatcher.ignoreAllButCloseOk())
        {
            dispatcher.receiveQueueDeclare(queue, passive, durable, exclusive, autoDelete, nowait, arguments);
        }
        if (arguments != null)
        {
            arguments.clearEncodedForm();
        }
    }
}
