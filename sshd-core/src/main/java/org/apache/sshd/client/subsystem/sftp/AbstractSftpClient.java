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
package org.apache.sshd.client.subsystem.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.client.subsystem.sftp.extensions.BuiltinSftpClientExtensions;
import org.apache.sshd.client.subsystem.sftp.extensions.SftpClientExtension;
import org.apache.sshd.client.subsystem.sftp.extensions.SftpClientExtensionFactory;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.subsystem.sftp.SftpConstants;
import org.apache.sshd.common.subsystem.sftp.extensions.ParserUtils;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.logging.AbstractLoggingBean;

import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_APPEND_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_READ_ATTRIBUTES;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_READ_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_WRITE_ATTRIBUTES;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_WRITE_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SFTP_V3;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SFTP_V4;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SFTP_V5;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SFTP_V6;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_ACCESSTIME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_ACMODTIME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_ALL;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_CREATETIME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_MODIFYTIME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_OWNERGROUP;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_PERMISSIONS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_SIZE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_SUBSECOND_TIMES;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_UIDGID;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_TYPE_DIRECTORY;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_TYPE_REGULAR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_TYPE_SYMLINK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_APPEND;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_CREAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_CREATE_NEW;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_CREATE_TRUNCATE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_EXCL;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_OPEN_EXISTING;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_OPEN_OR_CREATE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_READ;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_TRUNC;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_TRUNCATE_EXISTING;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_WRITE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_ATTRS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_BLOCK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_CLOSE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_FSETSTAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_FSTAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_HANDLE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_LINK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_LSTAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_MKDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_NAME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_OPEN;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_OPENDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_READ;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_READDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_READLINK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_REALPATH;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_REMOVE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_RENAME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_RENAME_ATOMIC;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_RENAME_OVERWRITE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_RMDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_SETSTAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_STAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_STATUS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_SYMLINK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_UNBLOCK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_WRITE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FX_EOF;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FX_OK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.S_IFDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.S_IFLNK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.S_IFREG;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class AbstractSftpClient extends AbstractLoggingBean implements SftpClient, RawSftpClient {

    private final AtomicReference<Map<String, Object>> parsedExtensionsHolder = new AtomicReference<>(null);

    protected AbstractSftpClient() {
        super();
    }

    @Override
    public String getName() {
        return SftpConstants.SFTP_SUBSYSTEM_NAME;
    }

    @Override
    public CloseableHandle open(String path) throws IOException {
        return open(path, Collections.<OpenMode>emptySet());
    }

    @Override
    public CloseableHandle open(String path, OpenMode... options) throws IOException {
        return open(path, GenericUtils.of(options));
    }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        rename(oldPath, newPath, Collections.<CopyMode>emptySet());
    }

    @Override
    public void rename(String oldPath, String newPath, CopyMode... options) throws IOException {
        rename(oldPath, newPath, GenericUtils.of(options));
    }

    @Override
    public InputStream read(String path) throws IOException {
        return read(path, DEFAULT_READ_BUFFER_SIZE);
    }

    @Override
    public InputStream read(String path, int bufferSize) throws IOException {
        return read(path, bufferSize, EnumSet.of(OpenMode.Read));
    }

    @Override
    public InputStream read(String path, OpenMode... mode) throws IOException {
        return read(path, DEFAULT_READ_BUFFER_SIZE, mode);
    }

    @Override
    public InputStream read(String path, int bufferSize, OpenMode... mode) throws IOException {
        return read(path, bufferSize, GenericUtils.of(mode));
    }

    @Override
    public InputStream read(String path, Collection<OpenMode> mode) throws IOException {
        return read(path, DEFAULT_READ_BUFFER_SIZE, mode);
    }

    @Override
    public int read(Handle handle, long fileOffset, byte[] dst) throws IOException {
        return read(handle, fileOffset, dst, 0, dst.length);
    }

    @Override
    public OutputStream write(String path) throws IOException {
        return write(path, DEFAULT_WRITE_BUFFER_SIZE);
    }

    @Override
    public OutputStream write(String path, int bufferSize) throws IOException {
        return write(path, bufferSize, EnumSet.of(OpenMode.Write, OpenMode.Create, OpenMode.Truncate));
    }

    @Override
    public OutputStream write(String path, OpenMode... mode) throws IOException {
        return write(path, DEFAULT_WRITE_BUFFER_SIZE, mode);
    }

    @Override
    public OutputStream write(String path, Collection<OpenMode> mode) throws IOException {
        return write(path, DEFAULT_WRITE_BUFFER_SIZE, mode);
    }

    @Override
    public OutputStream write(String path, int bufferSize, OpenMode... mode) throws IOException {
        return write(path, bufferSize, GenericUtils.of(mode));
    }

    @Override
    public void write(Handle handle, long fileOffset, byte[] src) throws IOException {
        write(handle, fileOffset, src, 0, src.length);
    }

    @Override
    public void symLink(String linkPath, String targetPath) throws IOException {
        link(linkPath, targetPath, true);
    }

    @Override
    public <E extends SftpClientExtension> E getExtension(Class<? extends E> extensionType) {
        Object instance = getExtension(BuiltinSftpClientExtensions.fromType(extensionType));
        if (instance == null) {
            return null;
        } else {
            return extensionType.cast(instance);
        }
    }

    @Override
    public SftpClientExtension getExtension(String extensionName) {
        return getExtension(BuiltinSftpClientExtensions.fromName(extensionName));
    }

    protected SftpClientExtension getExtension(SftpClientExtensionFactory factory) {
        if (factory == null) {
            return null;
        }

        Map<String, byte[]> extensions = getServerExtensions();
        Map<String, Object> parsed = getParsedServerExtensions(extensions);
        return factory.create(this, this, extensions, parsed);
    }

    protected Map<String, Object> getParsedServerExtensions() {
        return getParsedServerExtensions(getServerExtensions());
    }

    protected Map<String, Object> getParsedServerExtensions(Map<String, byte[]> extensions) {
        Map<String, Object> parsed = parsedExtensionsHolder.get();
        if (parsed == null) {
            parsed = ParserUtils.parse(extensions);
            if (parsed == null) {
                parsed = Collections.emptyMap();
            }
            parsedExtensionsHolder.set(parsed);
        }

        return parsed;
    }

    /**
     * Sends the specified command, waits for the response and then invokes {@link #checkStatus(Buffer)}
     * @param cmd The command to send
     * @param request The request {@link Buffer}
     * @throws IOException If failed to send, receive or check the returned status
     * @see #send(int, Buffer)
     * @see #receive(int)
     * @see #checkStatus(Buffer)
     */
    protected void checkStatus(int cmd, Buffer request) throws IOException {
        int reqId = send(cmd, request);
        Buffer response = receive(reqId);
        checkStatus(response);
    }

    /**
     * Checks if the incoming response is an {@code SSH_FXP_STATUS} one,
     * and if so whether the substatus is {@code SSH_FX_OK}.
     * @param buffer The received response {@link Buffer}
     * @throws IOException If response does not carry a status or carries
     * a bad status code
     * @see #checkStatus(int, int, String, String)
     */
    protected void checkStatus(Buffer buffer) throws IOException {
        int length = buffer.getInt();
        int type = buffer.getUByte();
        int id = buffer.getInt();
        if (type == SSH_FXP_STATUS) {
            int substatus = buffer.getInt();
            String msg = buffer.getString();
            String lang = buffer.getString();
            checkStatus(id, substatus, msg, lang);
        } else {
            throw new SshException("Unexpected SFTP packet received: type=" + type + ", id=" + id + ", length=" + length);
        }
    }

    /**
     * @param id The request id
     * @param substatus The sub-status value
     * @param msg The message
     * @param lang The language
     * @throws IOException if the sub-status is not {@code SSH_FX_OK}
     * @see #throwStatusException(int, int, String, String)
     */
    protected void checkStatus(int id, int substatus, String msg, String lang) throws IOException {
        if (log.isTraceEnabled()) {
            log.trace("checkStatus(id=" + id + ") status: " + substatus + " [" + lang + "]" + msg);
        }

        if (substatus != SSH_FX_OK) {
            throwStatusException(id, substatus, msg, lang);
        }
    }

    protected void throwStatusException(int id, int substatus, String msg, String lang) throws IOException {
        throw new SftpException(substatus, msg);
    }

    /**
     * @param cmd Command to be sent
     * @param request The {@link Buffer} containing the request
     * @return The received handle identifier
     * @throws IOException If failed to send/receive or process the response
     * @see #send(int, Buffer)
     * @see #receive(int)
     * @see #checkHandle(Buffer)
     */
    protected byte[] checkHandle(int cmd, Buffer request) throws IOException {
        int reqId = send(cmd, request);
        Buffer response = receive(reqId);
        return checkHandle(response);
    }

    protected byte[] checkHandle(Buffer buffer) throws IOException {
        int length = buffer.getInt();
        int type = buffer.getUByte();
        int id = buffer.getInt();
        if (type == SSH_FXP_HANDLE) {
            return ValidateUtils.checkNotNullAndNotEmpty(buffer.getBytes(), "Null/empty handle in buffer", GenericUtils.EMPTY_OBJECT_ARRAY);
        }

        if (type == SSH_FXP_STATUS) {
            int substatus = buffer.getInt();
            String msg = buffer.getString();
            String lang = buffer.getString();
            if (log.isTraceEnabled()) {
                log.trace("checkHandle(id={}) - status: {} [{}] {}", Integer.valueOf(id), Integer.valueOf(substatus), lang, msg);
            }
            throwStatusException(id, substatus, msg, lang);
        }

        throw new SshException("Unexpected SFTP packet received: type=" + type + ", id=" + id + ", length=" + length);
    }

    /**
     * @param cmd Command to be sent
     * @param request Request {@link Buffer}
     * @return The decoded response {@link Attributes}
     * @throws IOException If failed to send/receive or process the response
     * @see #send(int, Buffer)
     * @see #receive(int)
     * @see #checkAttributes(Buffer)
     */
    protected Attributes checkAttributes(int cmd, Buffer request) throws IOException {
        int reqId = send(cmd, request);
        Buffer response = receive(reqId);
        return checkAttributes(response);
    }

    protected Attributes checkAttributes(Buffer buffer) throws IOException {
        int length = buffer.getInt();
        int type = buffer.getUByte();
        int id = buffer.getInt();
        if (type == SSH_FXP_ATTRS) {
            return readAttributes(buffer);
        }

        if (type == SSH_FXP_STATUS) {
            int substatus = buffer.getInt();
            String msg = buffer.getString();
            String lang = buffer.getString();
            if (log.isTraceEnabled()) {
                log.trace("checkAttributes(id={}) - status: {} [{}] {}", Integer.valueOf(id), Integer.valueOf(substatus), lang, msg);
            }
            throwStatusException(id, substatus, msg, lang);
        }

        throw new SshException("Unexpected SFTP packet received: type=" + type + ", id=" + id + ", length=" + length);
    }

    /**
     * @param cmd Command to be sent
     * @param request The request {@link Buffer}
     * @return The retrieve name
     * @throws IOException If failed to send/receive or process the response
     * @see #send(int, Buffer)
     * @see #receive(int)
     * @see #checkOneName(Buffer)
     */
    protected String checkOneName(int cmd, Buffer request) throws IOException {
        int reqId = send(cmd, request);
        Buffer response = receive(reqId);
        return checkOneName(response);
    }

    protected String checkOneName(Buffer buffer) throws IOException {
        int length = buffer.getInt();
        int type = buffer.getUByte();
        int id = buffer.getInt();
        if (type == SSH_FXP_NAME) {
            int len = buffer.getInt();
            if (len != 1) {
                throw new SshException("SFTP error: received " + len + " names instead of 1");
            }
            String name = buffer.getString();
            String longName = null;
            int version = getVersion();
            if (version == SFTP_V3) {
                longName = buffer.getString();
            }
            Attributes attrs = readAttributes(buffer);
            if (log.isTraceEnabled()) {
                log.trace("checkOneName(id={}) ({})[{}]: {}", Integer.valueOf(id), name, longName, attrs);
            }
            return name;
        }

        if (type == SSH_FXP_STATUS) {
            int substatus = buffer.getInt();
            String msg = buffer.getString();
            String lang = buffer.getString();
            if (log.isTraceEnabled()) {
                log.trace("checkOneName(id={}) - status: {} [{}] {}", Integer.valueOf(id), Integer.valueOf(substatus), lang, msg);
            }

            throwStatusException(id, substatus, msg, lang);
        }

        throw new SshException("Unexpected SFTP packet received: type=" + type + ", id=" + id + ", length=" + length);
    }

    protected Attributes readAttributes(Buffer buffer) throws IOException {
        Attributes attrs = new Attributes();
        int flags = buffer.getInt();
        int version = getVersion();
        if (version == SFTP_V3) {
            if ((flags & SSH_FILEXFER_ATTR_SIZE) != 0) {
                attrs.flags.add(Attribute.Size);
                attrs.size = buffer.getLong();
            }
            if ((flags & SSH_FILEXFER_ATTR_UIDGID) != 0) {
                attrs.flags.add(Attribute.UidGid);
                attrs.uid = buffer.getInt();
                attrs.gid = buffer.getInt();
            }
            if ((flags & SSH_FILEXFER_ATTR_PERMISSIONS) != 0) {
                attrs.flags.add(Attribute.Perms);
                attrs.perms = buffer.getInt();
            }
            if ((flags & SSH_FILEXFER_ATTR_ACMODTIME) != 0) {
                attrs.flags.add(Attribute.AcModTime);
                attrs.atime = buffer.getInt();
                attrs.mtime = buffer.getInt();
            }
        } else if (version >= SFTP_V4) {
            attrs.type = buffer.getUByte();
            if ((flags & SSH_FILEXFER_ATTR_SIZE) != 0) {
                attrs.flags.add(Attribute.Size);
                attrs.size = buffer.getLong();
            }
            if ((flags & SSH_FILEXFER_ATTR_OWNERGROUP) != 0) {
                attrs.flags.add(Attribute.OwnerGroup);
                attrs.owner = buffer.getString();
                attrs.group = buffer.getString();
            }
            if ((flags & SSH_FILEXFER_ATTR_PERMISSIONS) != 0) {
                attrs.flags.add(Attribute.Perms);
                attrs.perms = buffer.getInt();
            }

            // update the permissions according to the type
            switch (attrs.type) {
                case SSH_FILEXFER_TYPE_REGULAR:
                    attrs.perms |= S_IFREG;
                    break;
                case SSH_FILEXFER_TYPE_DIRECTORY:
                    attrs.perms |= S_IFDIR;
                    break;
                case SSH_FILEXFER_TYPE_SYMLINK:
                    attrs.perms |= S_IFLNK;
                    break;
                default:    // do nothing
            }

            if ((flags & SSH_FILEXFER_ATTR_ACCESSTIME) != 0) {
                attrs.flags.add(Attribute.AccessTime);
                attrs.accessTime = readTime(buffer, flags);
                attrs.atime = (int) attrs.accessTime.to(TimeUnit.SECONDS);
            }
            if ((flags & SSH_FILEXFER_ATTR_CREATETIME) != 0) {
                attrs.flags.add(Attribute.CreateTime);
                attrs.createTime = readTime(buffer, flags);
                attrs.ctime = (int) attrs.createTime.to(TimeUnit.SECONDS);
            }
            if ((flags & SSH_FILEXFER_ATTR_MODIFYTIME) != 0) {
                attrs.flags.add(Attribute.ModifyTime);
                attrs.modifyTime = readTime(buffer, flags);
                attrs.mtime = (int) attrs.modifyTime.to(TimeUnit.SECONDS);
            }
            // TODO: acl
        } else {
            throw new IllegalStateException("readAttributes - unsupported version: " + version);
        }
        return attrs;
    }

    protected FileTime readTime(Buffer buffer, int flags) {
        long secs = buffer.getLong();
        long millis = secs * 1000;
        if ((flags & SSH_FILEXFER_ATTR_SUBSECOND_TIMES) != 0) {
            millis += buffer.getInt() / 1000000L;
        }
        return FileTime.from(millis, TimeUnit.MILLISECONDS);
    }

    protected void writeAttributes(Buffer buffer, Attributes attributes) throws IOException {
        int version = getVersion();
        if (version == SFTP_V3) {
            int flags = 0;
            for (Attribute a : attributes.flags) {
                switch (a) {
                    case Size:
                        flags |= SSH_FILEXFER_ATTR_SIZE;
                        break;
                    case UidGid:
                        flags |= SSH_FILEXFER_ATTR_UIDGID;
                        break;
                    case Perms:
                        flags |= SSH_FILEXFER_ATTR_PERMISSIONS;
                        break;
                    case AcModTime:
                        flags |= SSH_FILEXFER_ATTR_ACMODTIME;
                        break;
                    default:    // do nothing
                }
            }
            buffer.putInt(flags);
            if ((flags & SSH_FILEXFER_ATTR_SIZE) != 0) {
                buffer.putLong(attributes.size);
            }
            if ((flags & SSH_FILEXFER_ATTR_UIDGID) != 0) {
                buffer.putInt(attributes.uid);
                buffer.putInt(attributes.gid);
            }
            if ((flags & SSH_FILEXFER_ATTR_PERMISSIONS) != 0) {
                buffer.putInt(attributes.perms);
            }
            if ((flags & SSH_FILEXFER_ATTR_ACMODTIME) != 0) {
                buffer.putInt(attributes.atime);
                buffer.putInt(attributes.mtime);
            }
        } else if (version >= SFTP_V4) {
            int flags = 0;
            for (Attribute a : attributes.flags) {
                switch (a) {
                    case Size:
                        flags |= SSH_FILEXFER_ATTR_SIZE;
                        break;
                    case OwnerGroup:
                        flags |= SSH_FILEXFER_ATTR_OWNERGROUP;
                        break;
                    case Perms:
                        flags |= SSH_FILEXFER_ATTR_PERMISSIONS;
                        break;
                    case AccessTime:
                        flags |= SSH_FILEXFER_ATTR_ACCESSTIME;
                        break;
                    case ModifyTime:
                        flags |= SSH_FILEXFER_ATTR_MODIFYTIME;
                        break;
                    case CreateTime:
                        flags |= SSH_FILEXFER_ATTR_CREATETIME;
                        break;
                    default:    // do nothing
                }
            }
            buffer.putInt(flags);
            buffer.putByte((byte) attributes.type);
            if ((flags & SSH_FILEXFER_ATTR_SIZE) != 0) {
                buffer.putLong(attributes.size);
            }
            if ((flags & SSH_FILEXFER_ATTR_OWNERGROUP) != 0) {
                buffer.putString(attributes.owner != null ? attributes.owner : "OWNER@");
                buffer.putString(attributes.group != null ? attributes.group : "GROUP@");
            }
            if ((flags & SSH_FILEXFER_ATTR_PERMISSIONS) != 0) {
                buffer.putInt(attributes.perms);
            }
            if ((flags & SSH_FILEXFER_ATTR_ACCESSTIME) != 0) {
                buffer.putLong(attributes.accessTime.to(TimeUnit.SECONDS));
                if ((flags & SSH_FILEXFER_ATTR_SUBSECOND_TIMES) != 0) {
                    long nanos = attributes.accessTime.to(TimeUnit.NANOSECONDS);
                    nanos = nanos % TimeUnit.SECONDS.toNanos(1);
                    buffer.putInt((int) nanos);
                }
                buffer.putInt(attributes.atime);
            }
            if ((flags & SSH_FILEXFER_ATTR_CREATETIME) != 0) {
                buffer.putLong(attributes.createTime.to(TimeUnit.SECONDS));
                if ((flags & SSH_FILEXFER_ATTR_SUBSECOND_TIMES) != 0) {
                    long nanos = attributes.createTime.to(TimeUnit.NANOSECONDS);
                    nanos = nanos % TimeUnit.SECONDS.toNanos(1);
                    buffer.putInt((int) nanos);
                }
                buffer.putInt(attributes.atime);
            }
            if ((flags & SSH_FILEXFER_ATTR_MODIFYTIME) != 0) {
                buffer.putLong(attributes.modifyTime.to(TimeUnit.SECONDS));
                if ((flags & SSH_FILEXFER_ATTR_SUBSECOND_TIMES) != 0) {
                    long nanos = attributes.modifyTime.to(TimeUnit.NANOSECONDS);
                    nanos = nanos % TimeUnit.SECONDS.toNanos(1);
                    buffer.putInt((int) nanos);
                }
                buffer.putInt(attributes.atime);
            }
            // TODO: acl
        } else {
            throw new UnsupportedOperationException("writeAttributes(" + attributes + ") unsupported version: " + version);
        }
    }

    @Override
    public CloseableHandle open(String path, Collection<OpenMode> options) throws IOException {
        if (!isOpen()) {
            throw new IOException("open(" + path + ")[" + options + "] client is closed");
        }

        /*
         * Be consistent with FileChannel#open - if no mode specified then READ is assumed
         */
        if (GenericUtils.isEmpty(options)) {
            options = EnumSet.of(OpenMode.Read);
        }

        Buffer buffer = new ByteArrayBuffer(path.length() + Long.SIZE /* some extra fields */);
        buffer.putString(path);
        int version = getVersion();
        int mode = 0;
        if (version == SFTP_V3) {
            for (OpenMode m : options) {
                switch (m) {
                    case Read:
                        mode |= SSH_FXF_READ;
                        break;
                    case Write:
                        mode |= SSH_FXF_WRITE;
                        break;
                    case Append:
                        mode |= SSH_FXF_APPEND;
                        break;
                    case Create:
                        mode |= SSH_FXF_CREAT;
                        break;
                    case Truncate:
                        mode |= SSH_FXF_TRUNC;
                        break;
                    case Exclusive:
                        mode |= SSH_FXF_EXCL;
                        break;
                    default:    // do nothing
                }
            }
        } else {
            if (version >= SFTP_V5) {
                int access = 0;
                if (options.contains(OpenMode.Read)) {
                    access |= ACE4_READ_DATA | ACE4_READ_ATTRIBUTES;
                }
                if (options.contains(OpenMode.Write)) {
                    access |= ACE4_WRITE_DATA | ACE4_WRITE_ATTRIBUTES;
                }
                if (options.contains(OpenMode.Append)) {
                    access |= ACE4_APPEND_DATA;
                }
                buffer.putInt(access);
            }

            if (options.contains(OpenMode.Create) && options.contains(OpenMode.Exclusive)) {
                mode |= SSH_FXF_CREATE_NEW;
            } else if (options.contains(OpenMode.Create) && options.contains(OpenMode.Truncate)) {
                mode |= SSH_FXF_CREATE_TRUNCATE;
            } else if (options.contains(OpenMode.Create)) {
                mode |= SSH_FXF_OPEN_OR_CREATE;
            } else if (options.contains(OpenMode.Truncate)) {
                mode |= SSH_FXF_TRUNCATE_EXISTING;
            } else {
                mode |= SSH_FXF_OPEN_EXISTING;
            }
        }
        buffer.putInt(mode);
        writeAttributes(buffer, new Attributes());
        return new DefaultCloseableHandle(this, checkHandle(SSH_FXP_OPEN, buffer));
    }

    @Override
    public void close(Handle handle) throws IOException {
        if (!isOpen()) {
            throw new IOException("close(" + handle + ") client is closed");
        }

        byte[] id = handle.getIdentifier();
        Buffer buffer = new ByteArrayBuffer(id.length + Long.SIZE /* some extra fields */);
        buffer.putBytes(id);
        checkStatus(SSH_FXP_CLOSE, buffer);
    }

    @Override
    public void remove(String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("remove(" + path + ") client is closed");
        }

        Buffer buffer = new ByteArrayBuffer(path.length() + Long.SIZE /* some extra fields */);
        buffer.putString(path);
        checkStatus(SSH_FXP_REMOVE, buffer);
    }

    @Override
    public void rename(String oldPath, String newPath, Collection<CopyMode> options) throws IOException {
        if (!isOpen()) {
            throw new IOException("rename(" + oldPath + " => " + newPath + ")[" + options + "] client is closed");
        }

        Buffer buffer = new ByteArrayBuffer(oldPath.length() + newPath.length() + Long.SIZE /* some extra fields */);
        buffer.putString(oldPath);
        buffer.putString(newPath);

        int numOptions = GenericUtils.size(options);
        int version = getVersion();
        if (version >= SFTP_V5) {
            int opts = 0;
            if (numOptions > 0) {
                for (CopyMode opt : options) {
                    switch (opt) {
                        case Atomic:
                            opts |= SSH_FXP_RENAME_ATOMIC;
                            break;
                        case Overwrite:
                            opts |= SSH_FXP_RENAME_OVERWRITE;
                            break;
                        default:    // do nothing
                    }
                }
            }
            buffer.putInt(opts);
        } else if (numOptions > 0) {
            throw new UnsupportedOperationException("rename(" + oldPath + " => " + newPath + ")"
                    + " - copy options can not be used with this SFTP version: " + options);
        }
        checkStatus(SSH_FXP_RENAME, buffer);
    }

    @Override
    public int read(Handle handle, long fileOffset, byte[] dst, int dstOffset, int len) throws IOException {
        if (!isOpen()) {
            throw new IOException("read(" + handle + "/" + fileOffset + ")[" + dstOffset + "/" + len + "] client is closed");
        }

        byte[] id = handle.getIdentifier();
        Buffer buffer = new ByteArrayBuffer(id.length + Long.SIZE /* some extra fields */);
        buffer.putBytes(id);
        buffer.putLong(fileOffset);
        buffer.putInt(len);
        return checkData(SSH_FXP_READ, buffer, dstOffset, dst);
    }

    protected int checkData(int cmd, Buffer request, int dstOffset, byte[] dst) throws IOException {
        int reqId = send(cmd, request);
        Buffer response = receive(reqId);
        return checkData(response, dstOffset, dst);
    }

    protected int checkData(Buffer buffer, int dstoff, byte[] dst) throws IOException {
        int length = buffer.getInt();
        int type = buffer.getUByte();
        int id = buffer.getInt();
        if (type == SSH_FXP_DATA) {
            int len = buffer.getInt();
            buffer.getRawBytes(dst, dstoff, len);
            return len;
        }

        if (type == SSH_FXP_STATUS) {
            int substatus = buffer.getInt();
            String msg = buffer.getString();
            String lang = buffer.getString();
            if (log.isTraceEnabled()) {
                log.trace("checkData(id={}) - status: {} [{}] {}", Integer.valueOf(id), Integer.valueOf(substatus), lang, msg);
            }

            if (substatus == SSH_FX_EOF) {
                return -1;
            }

            throwStatusException(id, substatus, msg, lang);
        }

        throw new SshException("Unexpected SFTP packet received: type=" + type + ", id=" + id + ", length=" + length);
    }

    @Override
    public void write(Handle handle, long fileOffset, byte[] src, int srcOffset, int len) throws IOException {
        // do some bounds checking first
        if ((fileOffset < 0) || (srcOffset < 0) || (len < 0)) {
            throw new IllegalArgumentException("write(" + handle + ") please ensure all parameters "
                    + " are non-negative values: file-offset=" + fileOffset
                    + ", src-offset=" + srcOffset + ", len=" + len);
        }
        if ((srcOffset + len) > src.length) {
            throw new IllegalArgumentException("write(" + handle + ")"
                    + " cannot read bytes " + srcOffset + " to " + (srcOffset + len)
                    + " when array is only of length " + src.length);
        }
        if (!isOpen()) {
            throw new IOException("write(" + handle + "/" + fileOffset + ")[" + srcOffset + "/" + len + "] client is closed");
        }

        byte[] id = handle.getIdentifier();
        Buffer buffer = new ByteArrayBuffer(id.length + len + Long.SIZE /* some extra fields */);
        buffer.putBytes(id);
        buffer.putLong(fileOffset);
        buffer.putBytes(src, srcOffset, len);
        checkStatus(SSH_FXP_WRITE, buffer);
    }

    @Override
    public void mkdir(String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("mkdir(" + path + ") client is closed");
        }

        Buffer buffer = new ByteArrayBuffer(path.length() + Long.SIZE /* some extra fields */);
        buffer.putString(path);
        buffer.putInt(0);

        int version = getVersion();
        if (version != SFTP_V3) {
            buffer.putByte((byte) 0);
        }

        checkStatus(SSH_FXP_MKDIR, buffer);
    }

    @Override
    public void rmdir(String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("rmdir(" + path + ") client is closed");
        }

        Buffer buffer = new ByteArrayBuffer(path.length() + Long.SIZE /* some extra fields */);
        buffer.putString(path);
        checkStatus(SSH_FXP_RMDIR, buffer);
    }

    @Override
    public CloseableHandle openDir(String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("openDir(" + path + ") client is closed");
        }

        Buffer buffer = new ByteArrayBuffer(path.length() + Long.SIZE /* some extra fields */);
        buffer.putString(path);
        return new DefaultCloseableHandle(this, checkHandle(SSH_FXP_OPENDIR, buffer));
    }

    @Override
    public List<DirEntry> readDir(Handle handle) throws IOException {
        if (!isOpen()) {
            throw new IOException("readDir(" + handle + ") client is closed");
        }

        byte[] id = handle.getIdentifier();
        Buffer buffer = new ByteArrayBuffer(id.length + Byte.SIZE /* some extra fields */);
        buffer.putBytes(id);
        return checkDir(receive(send(SSH_FXP_READDIR, buffer)));
    }

    protected List<DirEntry> checkDir(Buffer buffer) throws IOException {
        int length = buffer.getInt();
        int type = buffer.getUByte();
        int id = buffer.getInt();
        if (type == SSH_FXP_NAME) {
            int len = buffer.getInt();
            List<DirEntry> entries = new ArrayList<DirEntry>(len);
            for (int i = 0; i < len; i++) {
                String name = buffer.getString();
                int version = getVersion();
                String longName = (version == SFTP_V3) ? buffer.getString() : null;
                Attributes attrs = readAttributes(buffer);
                if (log.isTraceEnabled()) {
                    log.trace("checkDir(id={})[{}] ({})[{}]: {}", Integer.valueOf(id), Integer.valueOf(i), name, longName, attrs);
                }

                entries.add(new DirEntry(name, longName, attrs));
            }
            return entries;
        }

        if (type == SSH_FXP_STATUS) {
            int substatus = buffer.getInt();
            String msg = buffer.getString();
            String lang = buffer.getString();
            if (log.isTraceEnabled()) {
                log.trace("checkDir(id={}) - status: {} [{}] {}", Integer.valueOf(id), Integer.valueOf(substatus), lang, msg);
            }

            if (substatus == SSH_FX_EOF) {
                return null;
            }

            throwStatusException(id, substatus, msg, lang);
        }

        throw new SshException("Unexpected SFTP packet received: type=" + type + ", id=" + id + ", length=" + length);
    }

    @Override
    public String canonicalPath(String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("canonicalPath(" + path + ") client is closed");
        }

        Buffer buffer = new ByteArrayBuffer();
        buffer.putString(path);
        return checkOneName(SSH_FXP_REALPATH, buffer);
    }

    @Override
    public Attributes stat(String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("stat(" + path + ") client is closed");
        }

        Buffer buffer = new ByteArrayBuffer();
        buffer.putString(path);

        int version = getVersion();
        if (version >= SFTP_V4) {
            buffer.putInt(SSH_FILEXFER_ATTR_ALL);
        }

        return checkAttributes(SSH_FXP_STAT, buffer);
    }

    @Override
    public Attributes lstat(String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("lstat(" + path + ") client is closed");
        }

        Buffer buffer = new ByteArrayBuffer();
        buffer.putString(path);

        int version = getVersion();
        if (version >= SFTP_V4) {
            buffer.putInt(SSH_FILEXFER_ATTR_ALL);
        }

        return checkAttributes(SSH_FXP_LSTAT, buffer);
    }

    @Override
    public Attributes stat(Handle handle) throws IOException {
        if (!isOpen()) {
            throw new IOException("stat(" + handle + ") client is closed");
        }

        byte[] id = handle.getIdentifier();
        Buffer buffer = new ByteArrayBuffer(id.length + Byte.SIZE /* a bit extra */);
        buffer.putBytes(id);

        int version = getVersion();
        if (version >= SFTP_V4) {
            buffer.putInt(SSH_FILEXFER_ATTR_ALL);
        }

        return checkAttributes(SSH_FXP_FSTAT, buffer);
    }

    @Override
    public void setStat(String path, Attributes attributes) throws IOException {
        if (!isOpen()) {
            throw new IOException("setStat(" + path + ")[" + attributes + "] client is closed");
        }

        Buffer buffer = new ByteArrayBuffer();
        buffer.putString(path);
        writeAttributes(buffer, attributes);
        checkStatus(SSH_FXP_SETSTAT, buffer);
    }

    @Override
    public void setStat(Handle handle, Attributes attributes) throws IOException {
        if (!isOpen()) {
            throw new IOException("setStat(" + handle + ")[" + attributes + "] client is closed");
        }

        byte[] id = handle.getIdentifier();
        Buffer buffer = new ByteArrayBuffer(id.length + (2 * Long.SIZE) /* some extras */);
        buffer.putBytes(id);
        writeAttributes(buffer, attributes);
        checkStatus(SSH_FXP_FSETSTAT, buffer);
    }

    @Override
    public String readLink(String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("readLink(" + path + ") client is closed");
        }

        Buffer buffer = new ByteArrayBuffer(path.length() + Long.SIZE /* some extra fields */);
        buffer.putString(path);
        return checkOneName(SSH_FXP_READLINK, buffer);
    }

    @Override
    public void link(String linkPath, String targetPath, boolean symbolic) throws IOException {
        if (!isOpen()) {
            throw new IOException("link(" + linkPath + " => " + targetPath + ")[symbolic=" + symbolic + "] client is closed");
        }

        Buffer buffer = new ByteArrayBuffer(linkPath.length() + targetPath.length() + Long.SIZE /* some extra fields */);
        int version = getVersion();
        if (version < SFTP_V6) {
            if (!symbolic) {
                throw new UnsupportedOperationException("Hard links are not supported in sftp v" + version);
            }
            buffer.putString(targetPath);
            buffer.putString(linkPath);
            checkStatus(SSH_FXP_SYMLINK, buffer);
        } else {
            buffer.putString(targetPath);
            buffer.putString(linkPath);
            buffer.putBoolean(symbolic);
            checkStatus(SSH_FXP_LINK, buffer);
        }
    }

    @Override
    public void lock(Handle handle, long offset, long length, int mask) throws IOException {
        if (!isOpen()) {
            throw new IOException("lock(" + handle + ")[offset=" + offset + ", length=" + length + ", mask=0x" + Integer.toHexString(mask) + "] client is closed");
        }

        byte[] id = handle.getIdentifier();
        Buffer buffer = new ByteArrayBuffer(id.length + Long.SIZE /* a bit extra */);
        buffer.putBytes(id);
        buffer.putLong(offset);
        buffer.putLong(length);
        buffer.putInt(mask);
        checkStatus(SSH_FXP_BLOCK, buffer);
    }

    @Override
    public void unlock(Handle handle, long offset, long length) throws IOException {
        if (!isOpen()) {
            throw new IOException("unlock" + handle + ")[offset=" + offset + ", length=" + length + "] client is closed");
        }

        byte[] id = handle.getIdentifier();
        Buffer buffer = new ByteArrayBuffer(id.length + Long.SIZE /* a bit extra */);
        buffer.putBytes(id);
        buffer.putLong(offset);
        buffer.putLong(length);
        checkStatus(SSH_FXP_UNBLOCK, buffer);
    }

    @Override
    public Iterable<DirEntry> readDir(final String path) throws IOException {
        if (!isOpen()) {
            throw new IOException("readDir(" + path + ") client is closed");
        }
        return new SftpIterableDirEntry(this, path);
    }

    @Override
    public InputStream read(final String path, final int bufferSize, final Collection<OpenMode> mode) throws IOException {
        if (bufferSize < MIN_READ_BUFFER_SIZE) {
            throw new IllegalArgumentException("Insufficient read buffer size: " + bufferSize + ", min.=" + MIN_READ_BUFFER_SIZE);
        }

        if (!isOpen()) {
            throw new IOException("read(" + path + ")[" + mode + "] size=" + bufferSize + ": client is closed");
        }

        return new SftpInputStreamWithChannel(this, bufferSize, path, mode);
    }

    @Override
    public OutputStream write(final String path, final int bufferSize, final Collection<OpenMode> mode) throws IOException {
        if (bufferSize < MIN_WRITE_BUFFER_SIZE) {
            throw new IllegalArgumentException("Insufficient write buffer size: " + bufferSize + ", min.=" + MIN_WRITE_BUFFER_SIZE);
        }

        if (!isOpen()) {
            throw new IOException("write(" + path + ")[" + mode + "] size=" + bufferSize + ": client is closed");
        }

        return new SftpOutputStreamWithChannel(this, bufferSize, path, mode);
    }
}
