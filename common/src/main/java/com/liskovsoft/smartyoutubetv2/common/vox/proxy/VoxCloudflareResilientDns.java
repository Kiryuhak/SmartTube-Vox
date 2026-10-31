package com.liskovsoft.smartyoutubetv2.common.vox.proxy;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import okhttp3.Dns;

/**
 * Resilient DNS resolver for Cloudflare Edge Services (such as *.workers.dev).
 *
 * Problem:
 * In certain network environments (notably Russia / TSPU / DPI filtering),
 * the default Cloudflare Anycast IP prefix 188.114.0.0/16 (188.114.96.0 - 188.114.97.255)
 * is systematically blocked on port 443 (TCP SYN timeout), while other Cloudflare Anycast
 * subnets (such as 172.67.0.0/16 and 104.21.0.0/16) are fully accessible and route correctly.
 *
 * Solution:
 * When resolving *.workers.dev domains, if system DNS returns 188.114.x.x addresses
 * (or fails with UnknownHostException), this resolver prioritizes unblocked Cloudflare
 * edge IP addresses (172.67.180.1, 104.21.23.1). For all other domains, standard system
 * DNS resolution is used without modification.
 */
public class VoxCloudflareResilientDns implements Dns {
    private static final String BLOCKED_CF_PREFIX = "188.114.";
    private static final String WORKERS_DEV_SUFFIX = ".workers.dev";

    // Known unblocked Cloudflare Anycast IPv4 edge addresses
    private static final List<String> RESILIENT_IPS = Arrays.asList(
            "172.67.180.1",
            "172.67.180.2",
            "104.21.23.1",
            "104.21.23.2"
    );

    private final Dns mSystemDns;

    public VoxCloudflareResilientDns() {
        this(Dns.SYSTEM);
    }

    @VisibleForTesting
    public VoxCloudflareResilientDns(@NonNull Dns systemDns) {
        this.mSystemDns = systemDns;
    }

    @Override
    @NonNull
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        if (!isCloudflareWorker(hostname)) {
            return mSystemDns.lookup(hostname);
        }

        List<InetAddress> systemAddresses = null;
        try {
            systemAddresses = mSystemDns.lookup(hostname);
        } catch (UnknownHostException e) {
            // System DNS failed to resolve *.workers.dev; fall back to resilient IPs
            return getResilientAddresses();
        }

        if (systemAddresses == null || systemAddresses.isEmpty()) {
            return getResilientAddresses();
        }

        boolean hasBlockedIp = false;
        for (InetAddress addr : systemAddresses) {
            if (addr.getHostAddress().startsWith(BLOCKED_CF_PREFIX)) {
                hasBlockedIp = true;
                break;
            }
        }

        if (!hasBlockedIp) {
            return systemAddresses;
        }

        // System DNS returned blocked 188.114.x.x addresses:
        // Prioritize unblocked Anycast IPs, then append non-blocked system addresses
        List<InetAddress> prioritized = new ArrayList<>(getResilientAddresses());
        for (InetAddress addr : systemAddresses) {
            if (!addr.getHostAddress().startsWith(BLOCKED_CF_PREFIX)) {
                prioritized.add(addr);
            }
        }
        return prioritized;
    }

    private static boolean isCloudflareWorker(String hostname) {
        if (hostname == null) return false;
        String lower = hostname.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(WORKERS_DEV_SUFFIX) || lower.equals("workers.dev");
    }

    private static List<InetAddress> getResilientAddresses() {
        List<InetAddress> list = new ArrayList<>();
        for (String ip : RESILIENT_IPS) {
            try {
                list.add(InetAddress.getByName(ip));
            } catch (UnknownHostException ignored) {
            }
        }
        return list;
    }
}
