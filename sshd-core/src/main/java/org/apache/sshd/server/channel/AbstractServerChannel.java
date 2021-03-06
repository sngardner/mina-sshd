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
package org.apache.sshd.server.channel;

import java.io.IOException;

import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.AbstractChannel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class AbstractServerChannel extends AbstractChannel implements ServerChannel {

    protected boolean exitStatusSent;

    protected AbstractServerChannel() {
        this("");
    }

    protected AbstractServerChannel(String discriminator) {
        super(discriminator, false);
    }

    @Override
    public OpenFuture open(int recipient, int rwSize, int packetSize, Buffer buffer) {
        this.recipient = recipient;

        Session s = getSession();
        FactoryManager manager = ValidateUtils.checkNotNull(s.getFactoryManager(), "No factory manager");
        this.remoteWindow.init(rwSize, packetSize, manager.getProperties());
        configureWindow();
        return doInit(buffer);
    }

    @Override
    public void handleOpenSuccess(int recipient, int rwSize, int packetSize, Buffer buffer) throws IOException {
        throw new UnsupportedOperationException("handleOpenSuccess(" + recipient + "," + rwSize + "," + packetSize + ") N/A");
    }

    @Override
    public void handleOpenFailure(Buffer buffer) {
        throw new UnsupportedOperationException("handleOpenFailure() N/A");
    }

    protected OpenFuture doInit(Buffer buffer) {
        ChannelListener listener = getChannelListenerProxy();
        OpenFuture f = new DefaultOpenFuture(this);
        try {
            listener.channelOpenSuccess(this);
            f.setOpened();
        } catch (RuntimeException t) {
            Throwable e = GenericUtils.peelException(t);
            try {
                listener.channelOpenFailure(this, e);
            } catch (Throwable ignored) {
                log.warn("doInit({}) failed ({}) to inform listener of open failure={}: {}",
                         this, ignored.getClass().getSimpleName(), e.getClass().getSimpleName(), ignored.getMessage());
            }
            f.setException(e);
        }

        return f;
    }

    protected void sendExitStatus(int v) throws IOException {
        if (!exitStatusSent) {
            exitStatusSent = true;
            if (log.isDebugEnabled()) {
                log.debug("Send SSH_MSG_CHANNEL_REQUEST exit-status on channel {}", Integer.valueOf(id));
            }

            Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST);
            buffer.putInt(recipient);
            buffer.putString("exit-status");
            buffer.putBoolean(false);   // want-reply - must be FALSE - see https://tools.ietf.org/html/rfc4254 section 6.10
            buffer.putInt(v);
            writePacket(buffer);
            notifyStateChanged();
        }
    }

}
