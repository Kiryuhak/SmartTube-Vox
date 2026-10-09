package com.liskovsoft.smartyoutubetv2.common.vox.proxy;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.Route;

/**
 * Изолированная фабрика сетевых клиентов для VOX / Яндекс перевода.
 * Гарантирует, что настройки прокси VOX применяются ТОЛЬКО к запросам перевода
 * и не затрагивают глобальное состояние JVM (ProxySelector/Authenticator) или трафик YouTube/SmartTube.
 */
public final class VoxHttpClientFactory {
    private static volatile VoxProxyConfig sOverrideConfig = null;
    private static volatile OkHttpClient sCachedVotClient = null;
    private static volatile OkHttpClient sCachedAudioClient = null;
    private static volatile OkHttpClient sCachedDirectMediaDownloadClient = null;
    private static volatile ConnectionPool sSharedDownloadConnectionPool = null;
    private static volatile VoxProxyConfig sCachedConfig = null;

    private VoxHttpClientFactory() {
    }

    @VisibleForTesting
    public static void setConfigOverrideForTesting(@Nullable VoxProxyConfig config) {
        sOverrideConfig = config;
        invalidateCache();
    }

    public static synchronized void invalidateCache() {
        sCachedVotClient = null;
        sCachedAudioClient = null;
        sCachedConfig = null;
        sCachedDirectMediaDownloadClient = null;
    }

    @NonNull
    public static synchronized ConnectionPool getSharedDownloadConnectionPool() {
        if (sSharedDownloadConnectionPool == null) {
            sSharedDownloadConnectionPool = new ConnectionPool(8, 5, TimeUnit.MINUTES);
        }
        return sSharedDownloadConnectionPool;
    }

    @NonNull
    public static VoxProxyConfig getConfig(@Nullable Context context) {
        if (sOverrideConfig != null) {
            return sOverrideConfig;
        }
        VotData data = VotData.instance(context);
        if (data != null) {
            return data.getVoxProxyConfig();
        }
        return VoxProxyConfig.DIRECT;
    }

    @NonNull
    public static VoxProxyConfig getConfig() {
        return getConfig(null);
    }

    @Nullable
    public static Proxy getJavaProxy(@Nullable Context context) {
        return getConfig(context).toJavaProxy();
    }

    @Nullable
    public static Proxy getJavaProxy() {
        return getJavaProxy(null);
    }

    /**
     * Открывает HttpURLConnection с применённым прокси VOX (если включён).
     */
    @NonNull
    public static HttpURLConnection openYandexConnection(@NonNull String urlString, @Nullable Context context) throws IOException {
        VoxProxyConfig config = getConfig(context);
        Proxy proxy = config.toJavaProxy();
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) (proxy != null ? url.openConnection(proxy) : url.openConnection());
        if (config.isEnabled() && config.getType() == VoxProxyConfig.Type.HTTP && config.getUsername() != null && config.getPassword() != null) {
            String userPass = config.getUsername() + ":" + config.getPassword();
            String basicAuth = "Basic " + android.util.Base64.encodeToString(userPass.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            conn.setRequestProperty("Proxy-Authorization", basicAuth);
        }
        return conn;
    }

    @NonNull
    public static HttpURLConnection openYandexConnection(@NonNull String urlString) throws IOException {
        return openYandexConnection(urlString, null);
    }

    /**
     * Возвращает OkHttpClient для запросов метаданных перевода (VotHttp).
     */
    @NonNull
    public static synchronized OkHttpClient getVotHttpClient(@Nullable Context context) {
        VoxProxyConfig currentConfig = getConfig(context);
        if (sCachedVotClient == null || !Objects.equals(sCachedConfig, currentConfig)) {
            sCachedConfig = currentConfig;
            sCachedVotClient = buildClient(currentConfig, 15, 20, 20);
            sCachedAudioClient = buildClient(currentConfig, 15, 30, 30);
        }
        return sCachedVotClient;
    }

    @NonNull
    public static OkHttpClient getVotHttpClient() {
        return getVotHttpClient(null);
    }

    /**
     * Возвращает OkHttpClient для потокового воспроизведения аудио перевода (SmartTubeYandexVotAudioStreamReader).
     */
    @NonNull
    public static synchronized OkHttpClient getYandexAudioHttpClient(@Nullable Context context) {
        VoxProxyConfig currentConfig = getConfig(context);
        if (sCachedAudioClient == null || !Objects.equals(sCachedConfig, currentConfig)) {
            sCachedConfig = currentConfig;
            sCachedVotClient = buildClient(currentConfig, 15, 20, 20);
            sCachedAudioClient = buildClient(currentConfig, 15, 30, 30);
        }
        return sCachedAudioClient;
    }

    @NonNull
    public static OkHttpClient getYandexAudioHttpClient() {
        return getYandexAudioHttpClient(null);
    }

    /**
     * Создает новый OkHttpClient для скачивания дорожки перевода VOX.
     */
    @NonNull
    public static OkHttpClient createTranslationDownloadClient(@Nullable Context context) {
        VoxProxyConfig currentConfig = getConfig(context);
        return buildClient(currentConfig, 15, 30, 30);
    }

    @NonNull
    public static OkHttpClient createTranslationDownloadClient() {
        return createTranslationDownloadClient(null);
    }

    /**
     * Возвращает переиспользуемый прямой OkHttpClient (без прокси) для скачивания видео и оригинального звука с YouTube.
     * Использует общий ConnectionPool и 15-секундный таймаут чтения для своевременного обнаружения зависаний (stall detection).
     */
    @NonNull
    public static synchronized OkHttpClient createDirectMediaDownloadClient() {
        if (sCachedDirectMediaDownloadClient == null) {
            sCachedDirectMediaDownloadClient = new OkHttpClient.Builder()
                    .connectionPool(getSharedDownloadConnectionPool())
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(25, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build();
        }
        return sCachedDirectMediaDownloadClient;
    }

    private static OkHttpClient buildClient(VoxProxyConfig config, long connectSec, long readSec, long writeSec) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionPool(getSharedDownloadConnectionPool())
                .connectTimeout(connectSec, TimeUnit.SECONDS)
                .readTimeout(readSec, TimeUnit.SECONDS)
                .writeTimeout(writeSec, TimeUnit.SECONDS)
                .followRedirects(true);

        Proxy proxy = config.toJavaProxy();
        if (proxy != null) {
            builder.proxy(proxy);
            if (config.getUsername() != null && config.getPassword() != null) {
                final String credential = Credentials.basic(config.getUsername(), config.getPassword());
                builder.proxyAuthenticator((Route route, Response response) -> {
                    if (response.request().header("Proxy-Authorization") != null) {
                        return null; // Don't retry if already attempted
                    }
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
            }
        }
        return builder.build();
    }
}
