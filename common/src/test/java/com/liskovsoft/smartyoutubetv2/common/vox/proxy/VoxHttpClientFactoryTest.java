package com.liskovsoft.smartyoutubetv2.common.vox.proxy;

import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VoxHttpClientFactoryTest {

    @Before
    public void setUp() {
        VoxHttpClientFactory.setConfigOverrideForTesting(null);
    }

    @After
    public void tearDown() {
        VoxHttpClientFactory.setConfigOverrideForTesting(null);
    }

    @Test
    public void testDirectClientsHaveNoProxy() {
        VoxHttpClientFactory.setConfigOverrideForTesting(VoxProxyConfig.DIRECT);

        OkHttpClient votClient = VoxHttpClientFactory.getVotHttpClient();
        assertNull("VOT client must have null proxy when direct", votClient.proxy());

        OkHttpClient audioClient = VoxHttpClientFactory.getYandexAudioHttpClient();
        assertNull("Audio client must have null proxy when direct", audioClient.proxy());

        OkHttpClient directMediaClient = VoxHttpClientFactory.createDirectMediaDownloadClient();
        assertNull("Media download client must ALWAYS be direct", directMediaClient.proxy());
    }

    @Test
    public void testHttpProxyRoutingIsolation() {
        VoxProxyConfig httpConfig = new VoxProxyConfig(true, VoxProxyConfig.Type.HTTP, "10.0.0.1", 8888);
        VoxHttpClientFactory.setConfigOverrideForTesting(httpConfig);

        // 1. VOX clients must have the configured HTTP proxy
        OkHttpClient votClient = VoxHttpClientFactory.getVotHttpClient();
        assertNotNull(votClient.proxy());
        assertEquals(Proxy.Type.HTTP, votClient.proxy().type());
        InetSocketAddress votAddr = (InetSocketAddress) votClient.proxy().address();
        assertEquals("10.0.0.1", votAddr.getHostString());
        assertEquals(8888, votAddr.getPort());

        OkHttpClient audioClient = VoxHttpClientFactory.getYandexAudioHttpClient();
        assertNotNull(audioClient.proxy());
        assertEquals(Proxy.Type.HTTP, audioClient.proxy().type());

        OkHttpClient transDownloadClient = VoxHttpClientFactory.createTranslationDownloadClient();
        assertNotNull(transDownloadClient.proxy());
        assertEquals(Proxy.Type.HTTP, transDownloadClient.proxy().type());

        // 2. ISOLATION CHECK: YouTube media download client MUST REMAIN DIRECT
        OkHttpClient youtubeMediaClient = VoxHttpClientFactory.createDirectMediaDownloadClient();
        assertNull("YouTube media download must remain direct even when VOX proxy is active", youtubeMediaClient.proxy());
    }

    @Test
    public void testSocksProxyRoutingIsolation() {
        VoxProxyConfig socksConfig = new VoxProxyConfig(true, VoxProxyConfig.Type.SOCKS, "192.168.1.50", 1080);
        VoxHttpClientFactory.setConfigOverrideForTesting(socksConfig);

        OkHttpClient votClient = VoxHttpClientFactory.getVotHttpClient();
        assertNotNull(votClient.proxy());
        assertEquals(Proxy.Type.SOCKS, votClient.proxy().type());
        InetSocketAddress votAddr = (InetSocketAddress) votClient.proxy().address();
        assertEquals("192.168.1.50", votAddr.getHostString());
        assertEquals(1080, votAddr.getPort());

        // ISOLATION CHECK: YouTube media client is direct
        OkHttpClient youtubeMediaClient = VoxHttpClientFactory.createDirectMediaDownloadClient();
        assertNull(youtubeMediaClient.proxy());
    }

    @Test
    public void testDynamicReconfiguration() {
        // Initially direct
        VoxHttpClientFactory.setConfigOverrideForTesting(VoxProxyConfig.DIRECT);
        assertNull(VoxHttpClientFactory.getVotHttpClient().proxy());

        // Switch to HTTP proxy
        VoxHttpClientFactory.setConfigOverrideForTesting(new VoxProxyConfig(true, VoxProxyConfig.Type.HTTP, "proxy.local", 3128));
        assertNotNull(VoxHttpClientFactory.getVotHttpClient().proxy());
        assertEquals(Proxy.Type.HTTP, VoxHttpClientFactory.getVotHttpClient().proxy().type());

        // Switch back to disabled
        VoxHttpClientFactory.setConfigOverrideForTesting(new VoxProxyConfig(false, VoxProxyConfig.Type.HTTP, "proxy.local", 3128));
        assertNull(VoxHttpClientFactory.getVotHttpClient().proxy());
    }
}
