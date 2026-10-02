package com.liskovsoft.smartyoutubetv2.common.vox.proxy;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VoxProxyConfigTest {

    @Test
    public void testDirectDefault() {
        VoxProxyConfig direct = VoxProxyConfig.DIRECT;
        assertFalse(direct.isEnabled());
        assertEquals(VoxProxyConfig.Type.DIRECT, direct.getType());
        assertNull(direct.toJavaProxy());
        assertEquals("Выкл", direct.toDisplayString());
    }

    @Test
    public void testHttpProxyValid() {
        VoxProxyConfig config = new VoxProxyConfig(true, VoxProxyConfig.Type.HTTP, "proxy.example.com", 8080);
        assertTrue(config.isEnabled());
        assertTrue(config.isValid());
        assertEquals(VoxProxyConfig.Type.HTTP, config.getType());
        assertEquals("proxy.example.com", config.getHost());
        assertEquals(8080, config.getPort());

        Proxy proxy = config.toJavaProxy();
        assertNotNull(proxy);
        assertEquals(Proxy.Type.HTTP, proxy.type());
        assertTrue(proxy.address() instanceof InetSocketAddress);
        InetSocketAddress addr = (InetSocketAddress) proxy.address();
        assertEquals("proxy.example.com", addr.getHostString());
        assertEquals(8080, addr.getPort());
        assertEquals("HTTP://proxy.example.com:8080", config.toDisplayString());
    }

    @Test
    public void testSocksProxyValid() {
        VoxProxyConfig config = new VoxProxyConfig(true, VoxProxyConfig.Type.SOCKS, "127.0.0.1", 1080);
        assertTrue(config.isEnabled());
        assertTrue(config.isValid());
        assertEquals(VoxProxyConfig.Type.SOCKS, config.getType());
        assertEquals("127.0.0.1", config.getHost());
        assertEquals(1080, config.getPort());

        Proxy proxy = config.toJavaProxy();
        assertNotNull(proxy);
        assertEquals(Proxy.Type.SOCKS, proxy.type());
        InetSocketAddress addr = (InetSocketAddress) proxy.address();
        assertEquals("127.0.0.1", addr.getHostString());
        assertEquals(1080, addr.getPort());
        assertEquals("SOCKS://127.0.0.1:1080", config.toDisplayString());
    }

    @Test
    public void testInvalidHostRejected() {
        assertFalse(VoxProxyConfig.isValidHost(null));
        assertFalse(VoxProxyConfig.isValidHost(""));
        assertFalse(VoxProxyConfig.isValidHost("   "));
        assertFalse(VoxProxyConfig.isValidHost("invalid host with spaces"));
        assertFalse(VoxProxyConfig.isValidHost("host\nwith\nnewlines"));

        VoxProxyConfig invalidHost = new VoxProxyConfig(true, VoxProxyConfig.Type.HTTP, "", 8080);
        assertFalse(invalidHost.isEnabled());
        assertFalse(invalidHost.isValid());
        assertNull(invalidHost.toJavaProxy());
    }

    @Test
    public void testInvalidPortRejected() {
        assertFalse(VoxProxyConfig.isValidPort(0));
        assertFalse(VoxProxyConfig.isValidPort(-1));
        assertFalse(VoxProxyConfig.isValidPort(65536));
        assertFalse(VoxProxyConfig.isValidPort(100000));
        assertTrue(VoxProxyConfig.isValidPort(1));
        assertTrue(VoxProxyConfig.isValidPort(80));
        assertTrue(VoxProxyConfig.isValidPort(443));
        assertTrue(VoxProxyConfig.isValidPort(8080));
        assertTrue(VoxProxyConfig.isValidPort(65535));

        VoxProxyConfig invalidPort = new VoxProxyConfig(true, VoxProxyConfig.Type.HTTP, "proxy.local", 0);
        assertFalse(invalidPort.isEnabled());
        assertFalse(invalidPort.isValid());
        assertNull(invalidPort.toJavaProxy());
    }

    @Test
    public void testDisabledConfigReturnsNullProxy() {
        VoxProxyConfig disabled = new VoxProxyConfig(false, VoxProxyConfig.Type.HTTP, "proxy.local", 8080);
        assertFalse(disabled.isEnabled());
        assertNull(disabled.toJavaProxy());
        assertEquals("Выкл", disabled.toDisplayString());
    }

    @Test
    public void testCredentialsNeverPrintedInToString() {
        VoxProxyConfig config = new VoxProxyConfig(
                true,
                VoxProxyConfig.Type.HTTP,
                "proxy.secret.com",
                8080,
                "secret_user",
                "super_secret_password_123"
        );
        String repr = config.toString();
        assertFalse(repr.contains("secret_user"));
        assertFalse(repr.contains("super_secret_password_123"));
        assertTrue(repr.contains("proxy.secret.com:8080"));
        assertTrue(repr.contains("auth=true"));
    }
}
