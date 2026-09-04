/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.backend;

import androidx.annotation.Nullable;

import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;
import com.wireguard.config.InetAddresses;
import com.wireguard.config.Interface;
import com.wireguard.config.ParseException;
import com.wireguard.util.NonNullForAll;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Runtime DNS policy supplied by the WireRoute application to a tunnel backend. */
@NonNullForAll
public final class DnsProtectionPolicy {
    public enum Mode {
        PROFILE,
        ENCRYPTED_HTTPS
    }

    /** Resolves the device-local policy for a profile when it is activated. */
    @FunctionalInterface
    public interface Provider {
        DnsProtectionPolicy getPolicy(String profileName) throws Exception;
    }

    private static final DnsProtectionPolicy PROFILE =
            new DnsProtectionPolicy(Mode.PROFILE, null, Collections.emptyList());
    private final List<String> bootstrapAddresses;
    private final Mode mode;
    @Nullable private final URI resolverUri;

    private DnsProtectionPolicy(
            final Mode mode,
            @Nullable final URI resolverUri,
            final List<String> bootstrapAddresses) {
        this.mode = mode;
        this.resolverUri = resolverUri;
        this.bootstrapAddresses = Collections.unmodifiableList(new ArrayList<>(bootstrapAddresses));
    }

    public static DnsProtectionPolicy profile() {
        return PROFILE;
    }

    public static DnsProtectionPolicy encryptedHttps(
            final String resolverUrl,
            final Collection<String> bootstrapAddresses) {
        final URI resolver;
        try {
            resolver = new URI(resolverUrl.trim());
        } catch (final URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "Enter a valid HTTPS resolver URL without a username, password, or fragment.",
                    exception);
        }
        if (!"https".equals(resolver.getScheme() == null
                ? null
                : resolver.getScheme().toLowerCase(Locale.ENGLISH))
                || resolver.getHost() == null
                || resolver.getHost().isBlank()
                || resolver.getUserInfo() != null
                || resolver.getFragment() != null
                || resolver.getPort() == 0
                || resolver.getPort() < -1
                || resolver.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "Enter a valid HTTPS resolver URL without a username, password, or fragment.");
        }

        final LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (final String rawAddress : bootstrapAddresses) {
            final String address = rawAddress.trim();
            if (address.isEmpty())
                continue;
            try {
                normalized.add(InetAddresses.parse(address).getHostAddress());
            } catch (final ParseException exception) {
                throw new IllegalArgumentException(
                        '\u2018' + address + "\u2019 is not a valid IPv4 or IPv6 bootstrap address.",
                        exception);
            }
        }
        return new DnsProtectionPolicy(
                Mode.ENCRYPTED_HTTPS,
                resolver,
                new ArrayList<>(normalized));
    }

    public List<String> getBootstrapAddresses() {
        return bootstrapAddresses;
    }

    /** Resolves an optional resolver hostname before Android installs the virtual DNS route. */
    public List<String> getEffectiveBootstrapAddresses() throws UnknownHostException {
        if (!bootstrapAddresses.isEmpty())
            return bootstrapAddresses;
        final LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (final InetAddress address : InetAddress.getAllByName(getResolverUri().getHost())) {
            if (resolved.size() == 8)
                break;
            resolved.add(address.getHostAddress());
        }
        if (resolved.isEmpty())
            throw new UnknownHostException(getResolverUri().getHost());
        return List.copyOf(resolved);
    }

    public Mode getMode() {
        return mode;
    }

    public URI getResolverUri() {
        return Objects.requireNonNull(resolverUri, "Profile DNS does not have a resolver URL");
    }

    /** Returns a transient configuration that points Android's resolver at the in-tunnel DoH endpoint. */
    public Config applyTo(final Config config) throws BadConfigException {
        if (mode == Mode.PROFILE)
            return config;

        final com.wireguard.config.Interface current = config.getInterface();
        final Interface.Builder builder = new Interface.Builder()
                .addAddresses(current.getAddresses())
                .addDnsServer(virtualDnsAddress())
                .addDnsSearchDomains(current.getDnsSearchDomains())
                .excludeApplications(current.getExcludedApplications())
                .includeApplications(current.getIncludedApplications())
                .setKeyPair(current.getKeyPair());
        if (current.getListenPort().isPresent())
            builder.setListenPort(current.getListenPort().get());
        if (current.getMtu().isPresent())
            builder.setMtu(current.getMtu().get());
        return new Config.Builder()
                .setInterface(builder.build())
                .addPeers(config.getPeers())
                .build();
    }

    public static InetAddress virtualDnsAddress() {
        try {
            return InetAddress.getByAddress(new byte[] {10, 64, 0, 53});
        } catch (final UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
