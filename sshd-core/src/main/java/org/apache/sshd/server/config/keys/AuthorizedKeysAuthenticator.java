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

package org.apache.sshd.server.config.keys;

import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.common.util.io.ModifiableFileWatcher;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.auth.pubkey.RejectAllPublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;

/**
 * Uses the authorized keys file to implement {@link PublickeyAuthenticator}
 * while automatically re-loading the keys if the file has changed when a
 * new authentication request is received. <B>Note:</B> by default, the only
 * validation of the username is that it is not {@code null}/empty - see
 * {@link #isValidUsername(String, ServerSession)}
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class AuthorizedKeysAuthenticator extends ModifiableFileWatcher implements PublickeyAuthenticator {
    private final AtomicReference<PublickeyAuthenticator> delegateHolder =  // assumes initially reject-all
            new AtomicReference<PublickeyAuthenticator>(RejectAllPublickeyAuthenticator.INSTANCE);

    public AuthorizedKeysAuthenticator(File file) {
        this(ValidateUtils.checkNotNull(file, "No file to watch").toPath());
    }

    public AuthorizedKeysAuthenticator(Path file) {
        this(file, IoUtils.EMPTY_LINK_OPTIONS);
    }

    public AuthorizedKeysAuthenticator(Path file, LinkOption... options) {
        super(file, options);
    }

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) {
        if (!isValidUsername(username, session)) {
            if (log.isDebugEnabled()) {
                log.debug("authenticate(" + username + ")[" + session + "][" + key.getAlgorithm() + "] invalid user name - file = " + getPath());
            }
            return false;
        }

        try {
            PublickeyAuthenticator delegate =
                    ValidateUtils.checkNotNull(resolvePublickeyAuthenticator(username, session), "No delegate");
            boolean accepted = delegate.authenticate(username, key, session);
            if (log.isDebugEnabled()) {
                log.debug("authenticate(" + username + ")[" + session + "][" + key.getAlgorithm() + "] accepted " + accepted + " from " + getPath());
            }

            return accepted;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("authenticate(" + username + ")[" + session + "][" + getPath() + "]"
                        + " failed (" + e.getClass().getSimpleName() + ")"
                        + " to resolve delegate: " + e.getMessage());
            }

            return false;
        }
    }

    protected boolean isValidUsername(String username, ServerSession session) {
        return !GenericUtils.isEmpty(username);
    }

    protected PublickeyAuthenticator resolvePublickeyAuthenticator(String username, ServerSession session)
            throws IOException, GeneralSecurityException {
        if (checkReloadRequired()) {
            /* Start fresh - NOTE: if there is any error then we want to reject all attempts
             * since we don't want to remain with the previous data - safer that way
             */
            delegateHolder.set(RejectAllPublickeyAuthenticator.INSTANCE);

            Path path = getPath();
            if (exists()) {
                Collection<AuthorizedKeyEntry> entries = reloadAuthorizedKeys(path, username, session);
                if (GenericUtils.size(entries) > 0) {
                    delegateHolder.set(AuthorizedKeyEntry.fromAuthorizedEntries(entries));
                }
            } else {
                log.info("resolvePublickeyAuthenticator(" + username + ")[" + session + "] no authorized keys file at " + path);
            }
        }

        return delegateHolder.get();
    }

    protected Collection<AuthorizedKeyEntry> reloadAuthorizedKeys(Path path, String username, ServerSession session) throws IOException {
        Collection<AuthorizedKeyEntry> entries = AuthorizedKeyEntry.readAuthorizedKeys(path);
        log.info("reloadAuthorizedKeys(" + username + ")[" + session + "] loaded " + GenericUtils.size(entries) + " keys from " + path);
        updateReloadAttributes();
        return entries;
    }
}
