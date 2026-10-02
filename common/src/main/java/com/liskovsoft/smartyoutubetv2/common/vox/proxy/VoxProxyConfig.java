package com.liskovsoft.smartyoutubetv2.common.vox.proxy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Objects;

/**
 * Неизменяемая модель конфигурации изолированного прокси для VOX / Яндекс перевода.
 */
public final class VoxProxyConfig {
    public enum Type {
        DIRECT,
        HTTP,
        SOCKS
    }

    public static final VoxProxyConfig DIRECT = new VoxProxyConfig(false, Type.DIRECT, "", 0, null, null);

    private final boolean mEnabled;
    private final Type mType;
    private final String mHost;
    private final int mPort;
    private final String mUsername;
    private final String mPassword;

    public VoxProxyConfig(boolean enabled, @NonNull Type type, @Nullable String host, int port) {
        this(enabled, type, host, port, null, null);
    }

    public VoxProxyConfig(boolean enabled, @NonNull Type type, @Nullable String host, int port,
                          @Nullable String username, @Nullable String password) {
        mType = type != null ? type : Type.DIRECT;
        mHost = host != null ? host.trim() : "";
        mPort = port;
        mUsername = username != null && !username.trim().isEmpty() ? username.trim() : null;
        mPassword = password != null && !password.isEmpty() ? password : null;
        mEnabled = enabled && isValid();
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    @NonNull
    public Type getType() {
        return mType;
    }

    @NonNull
    public String getHost() {
        return mHost;
    }

    public int getPort() {
        return mPort;
    }

    @Nullable
    public String getUsername() {
        return mUsername;
    }

    @Nullable
    public String getPassword() {
        return mPassword;
    }

    public boolean isValid() {
        if (mType == Type.DIRECT) {
            return true;
        }
        return isValidHost(mHost) && isValidPort(mPort);
    }

    public static boolean isValidHost(@Nullable String host) {
        if (host == null) return false;
        String trimmed = host.trim();
        return !trimmed.isEmpty()
                && trimmed.length() <= 253
                && !trimmed.contains(" ")
                && !trimmed.contains("\n")
                && !trimmed.contains("\r");
    }

    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    @Nullable
    public Proxy toJavaProxy() {
        if (!mEnabled || mType == Type.DIRECT || !isValid()) {
            return null;
        }
        Proxy.Type javaType = mType == Type.SOCKS ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(javaType, InetSocketAddress.createUnresolved(mHost, mPort));
    }

    @NonNull
    public String toDisplayString() {
        if (!mEnabled || mType == Type.DIRECT) {
            return "Выкл";
        }
        return mType.name() + "://" + mHost + ":" + mPort;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VoxProxyConfig that = (VoxProxyConfig) o;
        return mEnabled == that.mEnabled &&
                mPort == that.mPort &&
                mType == that.mType &&
                Objects.equals(mHost, that.mHost) &&
                Objects.equals(mUsername, that.mUsername) &&
                Objects.equals(mPassword, that.mPassword);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mEnabled, mType, mHost, mPort, mUsername, mPassword);
    }

    @Override
    public String toString() {
        // Redacted for safe logging - NEVER log credentials
        if (!mEnabled || mType == Type.DIRECT) {
            return "VoxProxyConfig{DIRECT}";
        }
        return "VoxProxyConfig{" + mType + "://" + mHost + ":" + mPort + ", auth=" + (mUsername != null) + "}";
    }
}
