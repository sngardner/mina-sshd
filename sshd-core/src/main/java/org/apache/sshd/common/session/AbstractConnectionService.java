/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.common.session;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.agent.common.AgentForwardSupport;
import org.apache.sshd.client.channel.AbstractClientChannel;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.FactoryManagerUtils;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.RequestHandler;
import org.apache.sshd.common.forward.TcpipForwarder;
import org.apache.sshd.common.forward.TcpipForwarderFactory;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.Int2IntFunction;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.closeable.AbstractInnerCloseable;
import org.apache.sshd.server.channel.OpenChannelException;
import org.apache.sshd.server.x11.X11ForwardSupport;

import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_CLOSE;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_DATA;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_EOF;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_EXTENDED_DATA;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_FAILURE;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_OPEN;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_REQUEST;
import static org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_WINDOW_ADJUST;
import static org.apache.sshd.common.SshConstants.SSH_MSG_GLOBAL_REQUEST;
import static org.apache.sshd.common.SshConstants.SSH_MSG_REQUEST_FAILURE;
import static org.apache.sshd.common.SshConstants.SSH_MSG_REQUEST_SUCCESS;

/**
 * Base implementation of ConnectionService.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class AbstractConnectionService extends AbstractInnerCloseable implements ConnectionService {
    /**
     * Property that can be used to configure max. allowed concurrent active channels
     *
     * @see #registerChannel(Channel)
     */
    public static final String MAX_CONCURRENT_CHANNELS_PROP = "max-sshd-channels";
    public static final int DEFAULT_MAX_CHANNELS = Integer.MAX_VALUE;

    /**
     * Default growth factor function used to resize response buffers
     */
    public static final Int2IntFunction RESPONSE_BUFFER_GROWTH_FACTOR = Int2IntFunction.Utils.add(Byte.SIZE);

    /**
     * Map of channels keyed by the identifier
     */
    protected final Map<Integer, Channel> channels = new ConcurrentHashMap<>();
    /**
     * Next channel identifier
     */
    protected final AtomicInteger nextChannelId = new AtomicInteger(0);

    protected final AbstractSession session;

    /**
     * The tcpip forwarder
     */
    protected final TcpipForwarder tcpipForwarder;
    protected final AgentForwardSupport agentForward;
    protected final X11ForwardSupport x11Forward;
    protected boolean allowMoreSessions = true;

    protected AbstractConnectionService(Session session) {
        ValidateUtils.checkTrue(session instanceof AbstractSession, "Not an AbstractSession");
        this.session = (AbstractSession) session;
        FactoryManager manager = session.getFactoryManager();
        agentForward = new AgentForwardSupport(this);
        x11Forward = new X11ForwardSupport(this);

        TcpipForwarderFactory factory = ValidateUtils.checkNotNull(
                manager.getTcpipForwarderFactory(),
                "No forwarder factory",
                GenericUtils.EMPTY_OBJECT_ARRAY);
        tcpipForwarder = factory.create(this);
    }

    public Collection<Channel> getChannels() {
        return channels.values();
    }

    @Override
    public AbstractSession getSession() {
        return session;
    }

    @Override
    public void start() {
        // do nothing
    }

    @Override
    public TcpipForwarder getTcpipForwarder() {
        return tcpipForwarder;
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder()
                .sequential(tcpipForwarder, agentForward, x11Forward)
                .parallel(channels.values())
                .build();
    }

    protected int getNextChannelId() {
        return nextChannelId.getAndIncrement();
    }

    @Override
    public int registerChannel(Channel channel) throws IOException {
        int maxChannels = FactoryManagerUtils.getIntProperty(session, MAX_CONCURRENT_CHANNELS_PROP, DEFAULT_MAX_CHANNELS);
        int curSize = channels.size();
        if (curSize > maxChannels) {
            throw new IllegalStateException("Currently active channels (" + curSize + ") at max.: " + maxChannels);
        }

        int channelId = getNextChannelId();
        channel.init(this, session, channelId);
        synchronized (lock) {
            if (isClosing()) {
                throw new IllegalStateException("Session is being closed: " + toString());
            }

            channels.put(channelId, channel);
        }

        if (log.isDebugEnabled()) {
            log.debug("registerChannel(id={}) {}", Integer.valueOf(channelId), channel);
        }
        return channelId;
    }

    /**
     * Remove this channel from the list of managed channels
     *
     * @param channel the channel
     */
    @Override
    public void unregisterChannel(Channel channel) {
        channels.remove(channel.getId());
    }

    @Override
    public void process(int cmd, Buffer buffer) throws Exception {
        switch (cmd) {
            case SSH_MSG_CHANNEL_OPEN:
                channelOpen(buffer);
                break;
            case SSH_MSG_CHANNEL_OPEN_CONFIRMATION:
                channelOpenConfirmation(buffer);
                break;
            case SSH_MSG_CHANNEL_OPEN_FAILURE:
                channelOpenFailure(buffer);
                break;
            case SSH_MSG_CHANNEL_REQUEST:
                channelRequest(buffer);
                break;
            case SSH_MSG_CHANNEL_DATA:
                channelData(buffer);
                break;
            case SSH_MSG_CHANNEL_EXTENDED_DATA:
                channelExtendedData(buffer);
                break;
            case SSH_MSG_CHANNEL_FAILURE:
                channelFailure(buffer);
                break;
            case SSH_MSG_CHANNEL_WINDOW_ADJUST:
                channelWindowAdjust(buffer);
                break;
            case SSH_MSG_CHANNEL_EOF:
                channelEof(buffer);
                break;
            case SSH_MSG_CHANNEL_CLOSE:
                channelClose(buffer);
                break;
            case SSH_MSG_GLOBAL_REQUEST:
                globalRequest(buffer);
                break;
            case SSH_MSG_REQUEST_SUCCESS:
                requestSuccess(buffer);
                break;
            case SSH_MSG_REQUEST_FAILURE:
                requestFailure(buffer);
                break;
            default:
                throw new IllegalStateException("Unsupported command: " + cmd);
        }
    }

    @Override
    public void setAllowMoreSessions(boolean allow) {
        allowMoreSessions = allow;
    }

    public void channelOpenConfirmation(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_MSG_CHANNEL_OPEN_CONFIRMATION on channel {}", Integer.valueOf(channel.getId()));
        }
        int recipient = buffer.getInt();
        int rwsize = buffer.getInt();
        int rmpsize = buffer.getInt();
        channel.handleOpenSuccess(recipient, rwsize, rmpsize, buffer);
    }

    public void channelOpenFailure(Buffer buffer) throws IOException {
        AbstractClientChannel channel = (AbstractClientChannel) getChannel(buffer);
        int id = channel.getId();
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_MSG_CHANNEL_OPEN_FAILURE on channel {}", id);
        }
        channels.remove(id);
        channel.handleOpenFailure(buffer);
    }

    /**
     * Process incoming data on a channel
     *
     * @param buffer the buffer containing the data
     * @throws IOException if an error occurs
     */
    public void channelData(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleData(buffer);
    }

    /**
     * Process incoming extended data on a channel
     *
     * @param buffer the buffer containing the data
     * @throws IOException if an error occurs
     */
    public void channelExtendedData(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleExtendedData(buffer);
    }

    /**
     * Process a window adjust packet on a channel
     *
     * @param buffer the buffer containing the window adjustement parameters
     * @throws IOException if an error occurs
     */
    public void channelWindowAdjust(Buffer buffer) throws IOException {
        try {
            Channel channel = getChannel(buffer);
            channel.handleWindowAdjust(buffer);
        } catch (SshException e) {
            log.info("channelWindowAdjust error: {}", e.getMessage());
        }
    }

    /**
     * Process end of file on a channel
     *
     * @param buffer the buffer containing the packet
     * @throws IOException if an error occurs
     */
    public void channelEof(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleEof();
    }

    /**
     * Close a channel due to a close packet received
     *
     * @param buffer the buffer containing the packet
     * @throws IOException if an error occurs
     */
    public void channelClose(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleClose();
    }

    /**
     * Service a request on a channel
     *
     * @param buffer the buffer containing the request
     * @throws IOException if an error occurs
     */
    public void channelRequest(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleRequest(buffer);
    }

    /**
     * Process a failure on a channel
     *
     * @param buffer the buffer containing the packet
     * @throws IOException if an error occurs
     */
    public void channelFailure(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleFailure();
    }

    /**
     * Retrieve the channel designated by the given packet
     *
     * @param buffer the incoming packet
     * @return the target channel
     * @throws IOException if the channel does not exists
     */
    protected Channel getChannel(Buffer buffer) throws IOException {
        int recipient = buffer.getInt();
        Channel channel = channels.get(recipient);
        if (channel == null) {
            buffer.rpos(buffer.rpos() - 5);
            int cmd = buffer.getUByte();
            throw new SshException("Received " + cmd + " on unknown channel " + recipient);
        }
        return channel;
    }

    protected void channelOpen(Buffer buffer) throws Exception {
        String type = buffer.getString();
        final int id = buffer.getInt();
        final int rwsize = buffer.getInt();
        final int rmpsize = buffer.getInt();

        log.debug("Received SSH_MSG_CHANNEL_OPEN {}", type);

        if (isClosing()) {
            Buffer buf = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE);
            buf.putInt(id);
            buf.putInt(SshConstants.SSH_OPEN_CONNECT_FAILED);
            buf.putString("SSH server is shutting down: " + type);
            buf.putString("");
            session.writePacket(buf);
            return;
        }
        if (!allowMoreSessions) {
            Buffer buf = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE);
            buf.putInt(id);
            buf.putInt(SshConstants.SSH_OPEN_CONNECT_FAILED);
            buf.putString("additional sessions disabled");
            buf.putString("");
            session.writePacket(buf);
            return;
        }

        final Channel channel = NamedFactory.Utils.create(session.getFactoryManager().getChannelFactories(), type);
        if (channel == null) {
            Buffer buf = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE);
            buf.putInt(id);
            buf.putInt(SshConstants.SSH_OPEN_UNKNOWN_CHANNEL_TYPE);
            buf.putString("Unsupported channel type: " + type);
            buf.putString("");
            session.writePacket(buf);
            return;
        }

        final int channelId = registerChannel(channel);
        channel.open(id, rwsize, rmpsize, buffer).addListener(new SshFutureListener<OpenFuture>() {
            @Override
            public void operationComplete(OpenFuture future) {
                try {
                    if (future.isOpened()) {
                        Buffer buf = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION);
                        buf.putInt(id);
                        buf.putInt(channelId);
                        buf.putInt(channel.getLocalWindow().getSize());
                        buf.putInt(channel.getLocalWindow().getPacketSize());
                        session.writePacket(buf);
                    } else {
                        Throwable exception = future.getException();
                        if (exception != null) {
                            Buffer buf = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE);
                            buf.putInt(id);
                            if (exception instanceof OpenChannelException) {
                                buf.putInt(((OpenChannelException) exception).getReasonCode());
                                buf.putString(exception.getMessage());
                            } else {
                                buf.putInt(0);
                                buf.putString("Error opening channel: " + exception.getMessage());
                            }
                            buf.putString("");
                            session.writePacket(buf);
                        }
                    }
                } catch (IOException e) {
                    session.exceptionCaught(e);
                }
            }
        });
    }

    /**
     * Process global requests
     *
     * @param buffer The request {@link Buffer}
     * @throws Exception If failed to process the request
     */
    protected void globalRequest(Buffer buffer) throws Exception {
        String req = buffer.getString();
        boolean wantReply = buffer.getBoolean();
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_MSG_GLOBAL_REQUEST {} want-reply={}", req, Boolean.valueOf(wantReply));
        }

        List<RequestHandler<ConnectionService>> handlers = session.getFactoryManager().getGlobalRequestHandlers();
        if (GenericUtils.size(handlers) > 0) {
            for (RequestHandler<ConnectionService> handler : handlers) {
                RequestHandler.Result result;
                try {
                    result = handler.process(this, req, wantReply, buffer);
                } catch (Exception e) {
                    log.warn("Error processing global request " + req, e);
                    result = RequestHandler.Result.ReplyFailure;
                }

                // if Unsupported then check the next handler in line
                if (RequestHandler.Result.Unsupported.equals(result)) {
                    if (log.isTraceEnabled()) {
                        log.trace("{}#process({}): {}", handler.getClass().getSimpleName(), req, result);
                    }
                } else {
                    sendResponse(buffer, req, result, wantReply);
                    return;
                }
            }
        }

        log.warn("Unknown global request: {}", req);
        sendResponse(buffer, req, RequestHandler.Result.Unsupported, wantReply);
    }

    protected void sendResponse(Buffer buffer, String req, RequestHandler.Result result, boolean wantReply) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("sendResponse({}) result={}, want-reply={}", req, result, Boolean.valueOf(wantReply));
        }

        if (RequestHandler.Result.Replied.equals(result) || (!wantReply)) {
            return;
        }

        byte cmd = RequestHandler.Result.ReplySuccess.equals(result)
                 ? SshConstants.SSH_MSG_CHANNEL_SUCCESS
                 : SshConstants.SSH_MSG_CHANNEL_FAILURE;
        buffer.clear();
        // leave room for the SSH header
        buffer.ensureCapacity(5 + 1 + (Integer.SIZE / Byte.SIZE), RESPONSE_BUFFER_GROWTH_FACTOR);
        buffer.rpos(5);
        buffer.wpos(5);
        buffer.putByte(cmd);
        session.writePacket(buffer);
    }

    protected void requestSuccess(Buffer buffer) throws Exception {
        session.requestSuccess(buffer);
    }

    protected void requestFailure(Buffer buffer) throws Exception {
        session.requestFailure(buffer);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + session + "]";
    }

}
