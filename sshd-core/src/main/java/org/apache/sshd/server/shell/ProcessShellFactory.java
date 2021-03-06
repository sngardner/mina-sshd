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
package org.apache.sshd.server.shell;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.apache.sshd.common.Factory;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.logging.AbstractLoggingBean;
import org.apache.sshd.server.Command;

/**
 * A {@link Factory} of {@link Command} that will create a new process and bridge
 * the streams.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class ProcessShellFactory extends AbstractLoggingBean implements Factory<Command> {

    public enum TtyOptions {
        Echo,
        INlCr,
        ICrNl,
        ONlCr,
        OCrNl;

        public static final Set<TtyOptions> LINUX_OPTIONS =
                Collections.unmodifiableSet(EnumSet.of(TtyOptions.ONlCr));

        public static final Set<TtyOptions> WINDOWS_OPTIONS =
                Collections.unmodifiableSet(EnumSet.of(TtyOptions.Echo, TtyOptions.ICrNl, TtyOptions.ONlCr));

        public static Set<TtyOptions> resolveDefaultTtyOptions() {
            return resolveTtyOptions(OsUtils.isWin32());
        }

        public static Set<TtyOptions> resolveTtyOptions(boolean isWin32) {
            if (isWin32) {
                return WINDOWS_OPTIONS;
            } else {
                return LINUX_OPTIONS;
            }
        }
    }

    private String[] command;
    private final Set<TtyOptions> ttyOptions;

    public ProcessShellFactory() {
        this(GenericUtils.EMPTY_STRING_ARRAY);
    }

    public ProcessShellFactory(String[] command) {
        this(command, TtyOptions.resolveDefaultTtyOptions());
    }

    public ProcessShellFactory(String[] command, Collection<TtyOptions> ttyOptions) {
        this.command = command;
        this.ttyOptions = GenericUtils.isEmpty(ttyOptions) ? Collections.<TtyOptions>emptySet() : GenericUtils.of(ttyOptions);
    }

    public String[] getCommand() {
        return command;
    }

    public void setCommand(String[] command) {
        this.command = command;
    }

    @Override
    public Command create() {
        return new InvertedShellWrapper(new ProcessShell());
    }

    public class ProcessShell implements InvertedShell {

        private Process process;
        private TtyFilterOutputStream in;
        private TtyFilterInputStream out;
        private TtyFilterInputStream err;

        public ProcessShell() {
            super();
        }

        @SuppressWarnings("synthetic-access")
        @Override
        public void start(Map<String, String> env) throws IOException {
            String[] cmds = new String[command.length];
            for (int i = 0; i < cmds.length; i++) {
                if ("$USER".equals(command[i])) {
                    cmds[i] = env.get("USER");
                } else {
                    cmds[i] = command[i];
                }
            }
            ProcessBuilder builder = new ProcessBuilder(cmds);
            if (GenericUtils.size(env) > 0) {
                try {
                    Map<String, String> procEnv = builder.environment();
                    procEnv.putAll(env);
                } catch (Exception e) {
                    log.warn("Could not set environment for command=" + GenericUtils.join(cmds, ' '), e);
                }
            }

            log.info("Starting shell with command: '{}' and env: {}", builder.command(), builder.environment());
            process = builder.start();
            out = new TtyFilterInputStream(process.getInputStream());
            err = new TtyFilterInputStream(process.getErrorStream());
            in = new TtyFilterOutputStream(process.getOutputStream(), err);
        }

        @Override
        public OutputStream getInputStream() {
            return in;
        }

        @Override
        public InputStream getOutputStream() {
            return out;
        }

        @Override
        public InputStream getErrorStream() {
            return err;
        }

        @Override
        public boolean isAlive() {
            try {
                process.exitValue();
                return false;
            } catch (IllegalThreadStateException e) {
                return true;
            }
        }

        @Override
        public int exitValue() {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            if (process != null) {
                try {
                    process.destroy();
                } finally {
                    process = null;
                }
            }
        }

        protected class TtyFilterInputStream extends FilterInputStream {
            private Buffer buffer;
            private int lastChar;

            public TtyFilterInputStream(InputStream in) {
                super(in);
                buffer = new ByteArrayBuffer(32);
            }

            synchronized void write(int c) {
                buffer.putByte((byte) c);
            }

            synchronized void write(byte[] buf, int off, int len) {
                buffer.putBytes(buf, off, len);
            }

            @Override
            public int available() throws IOException {
                return super.available() + buffer.available();
            }

            @SuppressWarnings("synthetic-access")
            @Override
            public synchronized int read() throws IOException {
                int c;
                if (buffer.available() > 0) {
                    c = buffer.getByte();
                    buffer.compact();
                } else {
                    c = super.read();
                }
                if (c == '\n' && ttyOptions.contains(TtyOptions.ONlCr) && lastChar != '\r') {
                    c = '\r';
                    Buffer buf = new ByteArrayBuffer();
                    buf.putByte((byte) '\n');
                    buf.putBuffer(buffer);
                    buffer = buf;
                } else if (c == '\r' && ttyOptions.contains(TtyOptions.OCrNl)) {
                    c = '\n';
                }
                lastChar = c;
                return c;
            }

            @Override
            public synchronized int read(byte[] b, int off, int len) throws IOException {
                if (buffer.available() == 0) {
                    int nb = super.read(b, off, len);
                    buffer.putRawBytes(b, off, nb);
                }
                int nb = 0;
                while (nb < len && buffer.available() > 0) {
                    b[off + nb++] = (byte) read();
                }
                return nb;
            }
        }

        protected class TtyFilterOutputStream extends FilterOutputStream {
            private TtyFilterInputStream echo;

            public TtyFilterOutputStream(OutputStream out, TtyFilterInputStream echo) {
                super(out);
                this.echo = echo;
            }

            @SuppressWarnings("synthetic-access")
            @Override
            public void write(int c) throws IOException {
                if (c == '\n' && ttyOptions.contains(TtyOptions.INlCr)) {
                    c = '\r';
                } else if (c == '\r' && ttyOptions.contains(TtyOptions.ICrNl)) {
                    c = '\n';
                }
                super.write(c);
                if (ttyOptions.contains(TtyOptions.Echo)) {
                    echo.write(c);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                for (int i = off; i < len; i++) {
                    write(b[i]);
                }
            }
        }
    }

}
