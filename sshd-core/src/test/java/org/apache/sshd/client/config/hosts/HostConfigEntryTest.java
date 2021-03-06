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

package org.apache.sshd.client.config.hosts;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.util.test.BaseTestSupport;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class HostConfigEntryTest extends BaseTestSupport {
    public HostConfigEntryTest() {
        super();
    }

    @Test
    public void testHostWildcardPatternMatching() {
        String pkgName = getClass().getPackage().getName();
        String[] elements = GenericUtils.split(pkgName, '.');
        StringBuilder sb = new StringBuilder(pkgName.length() + Long.SIZE + 1).append(HostConfigEntry.WILDCARD_PATTERN);
        for (int index = elements.length - 1; index >= 0; index--) {
            sb.append('.').append(elements[index]);
        }

        String value = sb.toString();
        Pattern pattern = HostConfigEntry.toPattern(value);
        String domain = value.substring(1); // chomp the wildcard prefix
        for (String host : new String[] {
                getClass().getSimpleName(),
                getCurrentTestName(),
                getClass().getSimpleName() + "-" + getCurrentTestName(),
                getClass().getSimpleName() + "." + getCurrentTestName(),
            }) {
            sb.setLength(0); // start from scratch
            sb.append(host).append(domain);

            testCaseInsensitivePatternMatching(sb.toString(), pattern, true);
        }
    }

    @Test
    public void testIPAddressWildcardPatternMatching() {
        StringBuilder sb = new StringBuilder().append("10.0.0.");
        int sbLen = sb.length();

        Pattern pattern = HostConfigEntry.toPattern(sb.append(HostConfigEntry.WILDCARD_PATTERN));
        for (int v = 0; v <= 255; v++) {
            sb.setLength(sbLen);    // start from scratch
            sb.append(v);

            String address = sb.toString();
            assertTrue("No match for " + address, HostConfigEntry.isHostMatch(address, pattern));
        }
    }

    @Test
    public void testHostSingleCharPatternMatching() {
        String value = getCurrentTestName();
        StringBuilder sb = new StringBuilder(value);
        for (boolean restoreOriginal : new boolean[] { true, false }) {
            for (int index = 0; index < value.length(); index++) {
                sb.setCharAt(index, HostConfigEntry.SINGLE_CHAR_PATTERN);
                testCaseInsensitivePatternMatching(value, HostConfigEntry.toPattern(sb.toString()), true);
                if (restoreOriginal) {
                    sb.setCharAt(index, value.charAt(index));
                }
            }
        }
    }

    @Test
    public void testIPAddressSingleCharPatternMatching() {
        StringBuilder sb = new StringBuilder().append("10.0.0.");
        int sbLen = sb.length();

        for (int v = 0; v <= 255; v++) {
            sb.setLength(sbLen);    // start from scratch
            sb.append(v);

            String address = sb.toString();
            // replace the added digits with single char pattern
            for (int index = sbLen; index < sb.length(); index++) {
                sb.setCharAt(index, HostConfigEntry.SINGLE_CHAR_PATTERN);
            }
            String pattern = sb.toString();

            assertTrue("No match for " + address + " on pattern=" + pattern, HostConfigEntry.isHostMatch(address, pattern));
        }
    }

    @Test
    public void testIsValidPatternChar() {
        for (char ch = '\0'; ch <= ' '; ch++) {
            assertFalse("Unexpected valid character (0x" + Integer.toHexString(ch & 0xFF) + ")", HostConfigEntry.isValidPatternChar(ch));
        }

        for (char ch = 'a'; ch <= 'z'; ch++) {
            assertTrue("Valid character not recognized: " + String.valueOf(ch), HostConfigEntry.isValidPatternChar(ch));
        }

        for (char ch = 'A'; ch <= 'Z'; ch++) {
            assertTrue("Valid character not recognized: " + String.valueOf(ch), HostConfigEntry.isValidPatternChar(ch));
        }

        for (char ch = '0'; ch <= '9'; ch++) {
            assertTrue("Valid character not recognized: " + String.valueOf(ch), HostConfigEntry.isValidPatternChar(ch));
        }

        for (char ch : new char[] { '-', '_', '.', HostConfigEntry.SINGLE_CHAR_PATTERN, HostConfigEntry.WILDCARD_PATTERN }) {
            assertTrue("Valid character not recognized: " + String.valueOf(ch), HostConfigEntry.isValidPatternChar(ch));
        }

        for (char ch : new char[] {
                    '(', ')', '{', '}', '[', ']', '!', '@',
                    '#', '$', '^', '&', '%', '~', '<', '>',
                    ',', '/', '\\', '\'', '"', ':', ';' }) {
            assertFalse("Unexpected valid character: " + String.valueOf(ch), HostConfigEntry.isValidPatternChar(ch));
        }

        for (char ch = 0x7E; ch <= 0xFF; ch++) {
            assertFalse("Unexpected valid character (0x" + Integer.toHexString(ch & 0xFF) + ")", HostConfigEntry.isValidPatternChar(ch));
        }
    }

    @Test
    public void testResolvePort() {
        final int ORIGINAL_PORT = Short.MAX_VALUE;
        final int ENTRY_PORT = 7365;
        assertEquals("Mismatched entry port preference", ENTRY_PORT, HostConfigEntry.resolvePort(ORIGINAL_PORT, ENTRY_PORT));

        for (int entryPort : new int[] { -1, 0 }) {
            assertEquals("Non-preferred original port for entry port=" + entryPort, ORIGINAL_PORT, HostConfigEntry.resolvePort(ORIGINAL_PORT, entryPort));
        }
    }

    @Test
    public void testResolveUsername() {
        final String ORIGINAL_USER = getCurrentTestName();
        final String ENTRY_USER = getClass().getSimpleName();
        assertSame("Mismatched entry user preference", ENTRY_USER, HostConfigEntry.resolveUsername(ORIGINAL_USER, ENTRY_USER));

        for (String entryUser : new String[] { null, "" }) {
            assertSame("Non-preferred original user for entry user='" + entryUser + "'", ORIGINAL_USER, HostConfigEntry.resolveUsername(ORIGINAL_USER, entryUser));
        }
    }

    @Test
    public void testReadSimpleHostsConfigEntries() throws IOException {
        validateHostConfigEntries(readHostConfigEntries());
    }

    @Test
    public void testReadGlobalHostsConfigEntries() throws IOException {
        List<HostConfigEntry> entries = validateHostConfigEntries(readHostConfigEntries());
        assertTrue("Not enough entries read", GenericUtils.size(entries) > 1);

        // global entry MUST be 1st one
        HostConfigEntry globalEntry = entries.get(0);
        assertEquals("Mismatched global entry pattern", HostConfigEntry.ALL_HOSTS_PATTERN, globalEntry.getHost());

        for (int index = 1; index < entries.size(); index++) {
            HostConfigEntry entry = entries.get(index);
            assertFalse("No target host for " + entry, GenericUtils.isEmpty(entry.getHostName()));
            assertTrue("No target port for " + entry, entry.getPort() > 0);
            assertFalse("No username for " + entry, GenericUtils.isEmpty(entry.getUsername()));
            assertFalse("No identities for " + entry, GenericUtils.isEmpty(entry.getIdentities()));
            assertFalse("No properties for " + entry, GenericUtils.isEmpty(entry.getProperties()));
        }
    }

    @Test
    public void testReadMultipleHostPatterns() throws IOException {
        List<HostConfigEntry> entries = validateHostConfigEntries(readHostConfigEntries());
        assertEquals("Mismatched number of duplicates", 3, GenericUtils.size(entries));

        HostConfigEntry prototype = entries.get(0);
        String protoIds = GenericUtils.join(prototype.getIdentities(), ',');
        Collection<Map.Entry<String,String>> protoProps = prototype.getProperties().entrySet();
        for (int index = 1; index < entries.size(); index++) {
            HostConfigEntry entry = entries.get(index);
            assertEquals("Mismatched host name for " + entry, prototype.getHostName(), entry.getHostName());
            assertEquals("Mismatched port for " + entry, prototype.getPort(), entry.getPort());
            assertSame("Mismatched user for " + entry, prototype.getUsername(), entry.getUsername());
            assertEquals("Mismatched identities for " + entry, protoIds, GenericUtils.join(entry.getIdentities(), ','));

            Map<String,String> entryProps = entry.getProperties();
            assertEquals("Mismatched properties count for " + entry, GenericUtils.size(protoProps), GenericUtils.size(entryProps));

            for (Map.Entry<String,String> ppe : protoProps) {
                String key = ppe.getKey();
                String expected = ppe.getValue();
                String actual = entryProps.get(key);
                assertEquals("Mismatched value for " + key + " property of " + entry, expected, actual);
            }
        }
    }

    @Test
    public void testResolveIdentityFilePath() throws Exception {
        final String HOST = getClass().getSimpleName();
        final int PORT = 7365;
        final String USER = getCurrentTestName();

        Exception err = null;
        for (String pattern : new String[] {
                "~/.ssh/%h.key",
                "%d/.ssh/%h.key",
                "/home/%u/.ssh/id_rsa_%p",
                "/home/%u/.ssh/id_%r_rsa",
                "/home/%u/.ssh/%h/%l.key"
        }) {
            try {
                String result = HostConfigEntry.resolveIdentityFilePath(pattern, HOST, PORT, USER);
                System.out.append('\t').append(pattern).append(" => ").println(result);
            } catch(Exception e) {
                System.err.append("Failed (").append(e.getClass().getSimpleName())
                          .append(") to process pattern=").append(pattern)
                          .append(": ").println(e.getMessage());
                err = e;
            }
        }

        if (err != null) {
            throw err;
        }
    }

    @Test
    public void testFindBestMatch() {
        final String HOST = getCurrentTestName();
        HostConfigEntry expected = new HostConfigEntry(HOST, HOST, 7365, HOST);
        List<HostConfigEntry> matches = new ArrayList<>();
        matches.add(new HostConfigEntry(HostConfigEntry.ALL_HOSTS_PATTERN, getClass().getSimpleName(), Short.MAX_VALUE, getClass().getSimpleName()));
        matches.add(new HostConfigEntry(HOST + String.valueOf(HostConfigEntry.WILDCARD_PATTERN), getClass().getSimpleName(), Byte.MAX_VALUE, getClass().getSimpleName()));
        matches.add(expected);

        for (int index = 0; index < matches.size(); index++) {
            HostConfigEntry actual = HostConfigEntry.findBestMatch(matches);
            assertSame("Mismatched best match for " + matches, expected, actual);
            Collections.shuffle(matches);
        }
    }

    private static <C extends Collection<HostConfigEntry>> C validateHostConfigEntries(C entries) {
        assertFalse("No entries", GenericUtils.isEmpty(entries));

        for (HostConfigEntry entry : entries) {
            assertFalse("No pattern for " + entry, GenericUtils.isEmpty(entry.getHost()));
            assertFalse("No extra properties for " + entry, GenericUtils.isEmpty(entry.getProperties()));
        }

        return entries;
    }

    private List<HostConfigEntry> readHostConfigEntries() throws IOException {
        return readHostConfigEntries(getCurrentTestName() + ".config.txt");
    }

    private List<HostConfigEntry> readHostConfigEntries(String resourceName) throws IOException {
        URL url = getClass().getResource(resourceName);
        assertNotNull("Missing resource " + resourceName, url);
        return HostConfigEntry.readHostConfigEntries(url);
    }
    private static void testCaseInsensitivePatternMatching(String value, Pattern pattern, boolean expected) {
        for (int index = 0; index < value.length(); index++) {
            boolean actual = HostConfigEntry.isHostMatch(value, pattern);
            assertEquals("Mismatched match result for " + value + " on pattern=" + pattern.pattern(), expected, actual);
            value = shuffleCase(value);
        }
    }
}
