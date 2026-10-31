package com.liskovsoft.smartyoutubetv2.common.vox.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.Dns;

public class VoxCloudflareResilientDnsTest {

    @Test
    public void testNonWorkersDevHostnamesDelegateUnchanged() throws Exception {
        Dns mockSystemDns = hostname -> {
            if ("oauth.yandex.ru".equals(hostname)) {
                return Collections.singletonList(InetAddress.getByName("77.88.55.55"));
            }
            throw new UnknownHostException(hostname);
        };

        VoxCloudflareResilientDns resilientDns = new VoxCloudflareResilientDns(mockSystemDns);
        List<InetAddress> result = resilientDns.lookup("oauth.yandex.ru");

        assertEquals(1, result.size());
        assertEquals("77.88.55.55", result.get(0).getHostAddress());
    }

    @Test
    public void testBlockedCloudflarePrefixPrioritizesResilientIps() throws Exception {
        Dns mockBlockedDns = hostname -> Arrays.asList(
                InetAddress.getByName("188.114.97.1"),
                InetAddress.getByName("188.114.96.1")
        );

        VoxCloudflareResilientDns resilientDns = new VoxCloudflareResilientDns(mockBlockedDns);
        List<InetAddress> result = resilientDns.lookup("smarttube-vox-yandex-oauth-broker.amn2402.workers.dev");

        assertFalse(result.isEmpty());
        // First resolved IP MUST NOT be in the blocked 188.114. range
        assertFalse(result.get(0).getHostAddress().startsWith("188.114."));
        // First resolved IP is one of the verified resilient Anycast IPs
        assertEquals("172.67.180.1", result.get(0).getHostAddress());
    }

    @Test
    public void testUnknownHostExceptionFallsBackToResilientIps() throws Exception {
        Dns mockFailingDns = hostname -> {
            throw new UnknownHostException("DNS failure for " + hostname);
        };

        VoxCloudflareResilientDns resilientDns = new VoxCloudflareResilientDns(mockFailingDns);
        List<InetAddress> result = resilientDns.lookup("smarttube-vox-yandex-oauth-broker.amn2402.workers.dev");

        assertFalse(result.isEmpty());
        assertEquals("172.67.180.1", result.get(0).getHostAddress());
    }

    @Test
    public void testUnblockedWorkersDevKeepsSystemAddresses() throws Exception {
        Dns mockCleanDns = hostname -> Collections.singletonList(InetAddress.getByName("172.67.180.5"));

        VoxCloudflareResilientDns resilientDns = new VoxCloudflareResilientDns(mockCleanDns);
        List<InetAddress> result = resilientDns.lookup("custom.workers.dev");

        assertEquals(1, result.size());
        assertEquals("172.67.180.5", result.get(0).getHostAddress());
    }
}
