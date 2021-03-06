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
package org.apache.sshd.client.channel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.client.channel.exit.ExitSignalChannelRequestHandler;
import org.apache.sshd.client.channel.exit.ExitStatusChannelRequestHandler;
import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.channel.AbstractChannel;
import org.apache.sshd.common.channel.ChannelAsyncInputStream;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.io.IoInputStream;
import org.apache.sshd.common.io.IoOutputStream;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.EventNotifier;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.io.IoUtils;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class AbstractClientChannel extends AbstractChannel implements ClientChannel {

    protected final AtomicBoolean opened = new AtomicBoolean();
    protected final String type;

    protected Streaming streaming;

    protected ChannelAsyncOutputStream asyncIn;
    protected ChannelAsyncInputStream asyncOut;
    protected ChannelAsyncInputStream asyncErr;

    protected InputStream in;
    protected OutputStream invertedIn;
    protected OutputStream out;
    protected InputStream invertedOut;
    protected OutputStream err;
    protected InputStream invertedErr;
    protected final AtomicReference<Integer> exitStatusHolder = new AtomicReference<>(null);
    protected final AtomicReference<String> exitSignalHolder = new AtomicReference<>(null);
    protected int openFailureReason;
    protected String openFailureMsg;
    protected OpenFuture openFuture;

    protected AbstractClientChannel(String type) {
        super(true);
        this.type = type;
        this.streaming = Streaming.Sync;

        final EventNotifier<String> notifier = new EventNotifier<String>() {
            @SuppressWarnings("synthetic-access")
            @Override
            public void notifyEvent(String event) throws Exception {
                log.debug("notifyEvent({})", event);
                notifyStateChanged();
            }
        };
        addRequestHandler(new ExitStatusChannelRequestHandler(exitStatusHolder, notifier));
        addRequestHandler(new ExitSignalChannelRequestHandler(exitSignalHolder, notifier));
    }

    @Override
    public Streaming getStreaming() {
        return streaming;
    }

    @Override
    public void setStreaming(Streaming streaming) {
        this.streaming = streaming;
    }

    @Override
    public IoOutputStream getAsyncIn() {
        return asyncIn;
    }

    @Override
    public IoInputStream getAsyncOut() {
        return asyncOut;
    }

    @Override
    public IoInputStream getAsyncErr() {
        return asyncErr;
    }

    @Override
    public OutputStream getInvertedIn() {
        return invertedIn;
    }

    public InputStream getIn() {
        return in;
    }

    @Override
    public void setIn(InputStream in) {
        this.in = in;
    }

    @Override
    public InputStream getInvertedOut() {
        return invertedOut;
    }

    public OutputStream getOut() {
        return out;
    }

    @Override
    public void setOut(OutputStream out) {
        this.out = out;
    }

    @Override
    public InputStream getInvertedErr() {
        return invertedErr;
    }

    public OutputStream getErr() {
        return err;
    }

    @Override
    public void setErr(OutputStream err) {
        this.err = err;
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder()
                .when(openFuture)
                .run(new Runnable() {
                    @SuppressWarnings("synthetic-access")
                    @Override
                    public void run() {
                        // If the channel has not been opened yet,
                        // skip the SSH_MSG_CHANNEL_CLOSE exchange
                        if (openFuture == null) {
                            gracefulFuture.setClosed();
                        }
                        // Close inverted streams after
                        // If the inverted stream is closed before, there's a small time window
                        // in which we have:
                        //    ChannePipedInputStream#closed = true
                        //    ChannePipedInputStream#writerClosed = false
                        // which leads to an IOException("Pipe closed") when reading.
                        IoUtils.closeQuietly(in, out, err);
                        IoUtils.closeQuietly(invertedIn, invertedOut, invertedErr);
                    }
                })
                .parallel(asyncIn, asyncOut, asyncErr)
                .close(new GracefulChannelCloseable())
                .build();
    }

    @Override
    public int waitFor(int mask, long timeout) {
        long t = 0;
        synchronized (lock) {
            for (;;) {
                int cond = 0;
                if (openFuture != null && openFuture.isOpened()) {
                    cond |= ClientChannel.OPENED;
                }
                if (closeFuture.isClosed()) {
                    cond |= ClientChannel.CLOSED | ClientChannel.EOF;
                }
                if (isEofSignalled()) {
                    cond |= ClientChannel.EOF;
                }
                if (exitStatusHolder.get() != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("waitFor({}) mask=0x{} - exit status={}", this, Integer.toHexString(mask), exitStatusHolder);
                    }
                    cond |= ClientChannel.EXIT_STATUS;
                }
                if (exitSignalHolder.get() != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("waitFor({}) mask=0x{} - exit signal={}", this, Integer.toHexString(mask), exitSignalHolder);
                    }
                    cond |= ClientChannel.EXIT_SIGNAL;
                }
                if ((cond & mask) != 0) {
                    if (log.isTraceEnabled()) {
                        log.trace("WaitFor call returning on channel {}, mask=0x{}, cond=0x{}",
                                  this, Integer.toHexString(mask), Integer.toHexString(cond));
                    }
                    return cond;
                }

                if (timeout > 0L) {
                    if (t == 0L) {
                        t = System.currentTimeMillis() + timeout;
                    } else {
                        timeout = t - System.currentTimeMillis();
                        if (timeout <= 0L) {
                            if (log.isTraceEnabled()) {
                                log.trace("WaitFor call timeout on channel {}, mask=0x{}", this, Integer.toHexString(mask));
                            }
                            cond |= ClientChannel.TIMEOUT;
                            return cond;
                        }
                    }
                }

                if (log.isTraceEnabled()) {
                    log.trace("Waiting {} millis for lock on channel {}, mask={}, cond={}", timeout, this, mask, cond);
                }

                long nanoStart = System.nanoTime();
                try {
                    if (timeout > 0L) {
                        lock.wait(timeout);
                    } else {
                        lock.wait();
                    }

                    long nanoEnd = System.nanoTime();
                    long nanoDuration = nanoEnd - nanoStart;
                    if (log.isTraceEnabled()) {
                        log.trace("Lock notified on channel {} after {} nanos", this, nanoDuration);
                    }
                } catch (InterruptedException e) {
                    long nanoEnd = System.nanoTime();
                    long nanoDuration = nanoEnd - nanoStart;
                    if (log.isTraceEnabled()) {
                        log.trace("waitFor({}) mask={} - ignoring interrupted exception after {} nanos", this, Integer.toHexString(mask), nanoDuration);
                    }
                }
            }
        }
    }

    @Override
    public synchronized OpenFuture open() throws IOException {
        if (isClosing()) {
            throw new SshException("Session has been closed");
        }
        openFuture = new DefaultOpenFuture(lock);
        log.debug("Send SSH_MSG_CHANNEL_OPEN on channel {}", this);
        Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN);
        buffer.putString(type);
        buffer.putInt(id);
        buffer.putInt(localWindow.getSize());
        buffer.putInt(localWindow.getPacketSize());
        writePacket(buffer);
        return openFuture;
    }

    @Override
    public OpenFuture open(int recipient, int rwSize, int packetSize, Buffer buffer) {
        throw new UnsupportedOperationException("open(" + recipient + "," + rwSize + "," + packetSize + ") N/A");
    }

    @Override
    public void handleOpenSuccess(int recipient, int rwSize, int packetSize, Buffer buffer) {
        this.recipient = recipient;

        Session s = getSession();
        FactoryManager manager = ValidateUtils.checkNotNull(s.getFactoryManager(), "No factory manager");
        this.remoteWindow.init(rwSize, packetSize, manager.getProperties());
        ChannelListener listener = getChannelListenerProxy();
        try {
            doOpen();

            listener.channelOpenSuccess(this);
            this.opened.set(true);
            this.openFuture.setOpened();
        } catch (Throwable t) {
            Throwable e = GenericUtils.peelException(t);
            try {
                listener.channelOpenFailure(this, e);
            } catch (Throwable ignored) {
                log.warn("handleOpenSuccess({}) failed ({}) to inform listener of open failure={}: {}",
                         this, ignored.getClass().getSimpleName(), e.getClass().getSimpleName(), ignored.getMessage());
            }

            this.openFuture.setException(e);
            this.closeFuture.setClosed();
            this.doCloseImmediately();
        } finally {
            notifyStateChanged();
        }
    }

    protected abstract void doOpen() throws IOException;

    @Override
    public void handleOpenFailure(Buffer buffer) {
        int reason = buffer.getInt();
        String msg = buffer.getString();
        this.openFailureReason = reason;
        this.openFailureMsg = msg;
        this.openFuture.setException(new SshException(msg));
        this.closeFuture.setClosed();
        this.doCloseImmediately();
        notifyStateChanged();
    }

    @Override
    protected void doWriteData(byte[] data, int off, int len) throws IOException {
        // If we're already closing, ignore incoming data
        if (isClosing()) {
            return;
        }
        if (asyncOut != null) {
            asyncOut.write(new ByteArrayBuffer(data, off, len));
        } else if (out != null) {
            out.write(data, off, len);
            out.flush();
            if (invertedOut == null) {
                localWindow.consumeAndCheck(len);
            }
        } else {
            throw new IllegalStateException("No output stream for channel");
        }
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, int len) throws IOException {
        // If we're already closing, ignore incoming data
        if (isClosing()) {
            return;
        }
        if (asyncErr != null) {
            asyncErr.write(new ByteArrayBuffer(data, off, len));
        } else if (err != null) {
            err.write(data, off, len);
            err.flush();
            if (invertedErr == null) {
                localWindow.consumeAndCheck(len);
            }
        } else {
            throw new IllegalStateException("No error stream for channel");
        }
    }

    @Override
    public void handleWindowAdjust(Buffer buffer) throws IOException {
        super.handleWindowAdjust(buffer);
        if (asyncIn != null) {
            asyncIn.onWindowExpanded();
        }
    }

    @Override
    public Integer getExitStatus() {
        return exitStatusHolder.get();
    }
}
