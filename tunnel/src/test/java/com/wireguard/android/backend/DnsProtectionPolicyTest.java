/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.backend;

import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class DnsProtectionPolicyTest {
    @Test
    public void profilePolicyLeavesConfigurationUnchanged() throws IOException, BadConfigException {
        final Config config = workingConfig();

        assertSame(config, DnsProtectionPolicy.profile().applyTo(config));
    }

    @Test
    public void encryptedPolicyUsesVirtualResolverAndPreservesTunnelSettings()
            throws IOException, BadConfigException {
        final Config config = workingConfig();
        final DnsProtectionPolicy policy = DnsProtectionPolicy.encryptedHttps(
                "https://cloudflare-dns.com/dns-query",
                List.of("1.1.1.1", "1.0.0.1"));

        final Config runtimeConfig = policy.applyTo(config);

        assertEquals(List.of(DnsProtectionPolicy.virtualDnsAddress()),
                List.copyOf(runtimeConfig.getInterface().getDnsServers()));
        assertEquals(config.getInterface().getAddresses(), runtimeConfig.getInterface().getAddresses());
        assertEquals(config.getInterface().getDnsSearchDomains(),
                runtimeConfig.getInterface().getDnsSearchDomains());
        assertEquals(config.getInterface().getExcludedApplications(),
                runtimeConfig.getInterface().getExcludedApplications());
        assertEquals(config.getInterface().getIncludedApplications(),
                runtimeConfig.getInterface().getIncludedApplications());
        assertEquals(config.getInterface().getKeyPair(), runtimeConfig.getInterface().getKeyPair());
        assertEquals(config.getInterface().getListenPort(), runtimeConfig.getInterface().getListenPort());
        assertEquals(config.getInterface().getMtu(), runtimeConfig.getInterface().getMtu());
        assertEquals(config.getPeers(), runtimeConfig.getPeers());
        assertEquals("https://cloudflare-dns.com/dns-query", policy.getResolverUri().toASCIIString());
        assertEquals(2, policy.getBootstrapAddresses().size());
    }

    @Test
    public void encryptedPolicyRejectsUnsafeResolverUrlsAndHostnameBootstraps() {
        assertThrows(IllegalArgumentException.class, () ->
                DnsProtectionPolicy.encryptedHttps("http://dns.example/dns-query", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                DnsProtectionPolicy.encryptedHttps("https://user@dns.example/dns-query", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                DnsProtectionPolicy.encryptedHttps("https://dns.example/dns-query#fragment", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                DnsProtectionPolicy.encryptedHttps(
                        "https://dns.example/dns-query",
                        List.of("bootstrap.example")));
    }

    private Config workingConfig() throws IOException, BadConfigException {
        try (InputStream stream = Objects.requireNonNull(getClass().getClassLoader())
                .getResourceAsStream("working.conf")) {
            return Config.parse(stream);
        }
    }
}
