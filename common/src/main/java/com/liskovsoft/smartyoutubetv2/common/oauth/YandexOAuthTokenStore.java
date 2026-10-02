package com.liskovsoft.smartyoutubetv2.common.oauth;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Isolated, at-rest encrypted token store for Yandex OAuth credentials.
 * Credentials (access_token, refresh_token) are never stored in plaintext.
 * Failures fail closed and never crash the application.
 */
public class YandexOAuthTokenStore {
    private static final String TAG = "YandexOAuthTokenStore";
    private static final String PREFS_NAME = "vot_auth_secure";
    private static final String KEY_ALIAS = "vot_yandex_oauth_key";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final byte FORMAT_VERSION = 1;

    private static final String PREF_ENC_ACCESS_TOKEN = "sec_access_token";
    private static final String PREF_ENC_REFRESH_TOKEN = "sec_refresh_token";
    private static final String PREF_EXPIRES_AT = "sec_expires_at";
    private static final String PREF_AUTH_STATE = "sec_auth_state";

    @SuppressLint("StaticFieldLeak")
    private static YandexOAuthTokenStore sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final SecureRandom mRandom = new SecureRandom();
    private SecretKey mCachedKey;
    private boolean mKeyStoreFailed;

    public static synchronized YandexOAuthTokenStore instance(Context context) {
        if (sInstance == null) {
            sInstance = new YandexOAuthTokenStore(context.getApplicationContext());
        }
        return sInstance;
    }

    @VisibleForTesting
    public static synchronized void resetForTesting() {
        sInstance = null;
    }

    @VisibleForTesting
    public YandexOAuthTokenStore(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SecretKey sTestSecretKey;

    @VisibleForTesting
    public static synchronized void setTestSecretKey(@Nullable SecretKey key) {
        sTestSecretKey = key;
    }

    public synchronized void saveTokens(@Nullable String accessToken, @Nullable String refreshToken, long expiresInSeconds) {
        String normalizedAccess = normalizeToken(accessToken);
        String normalizedRefresh = normalizeToken(refreshToken);

        String encAccess = null;
        if (!TextUtils.isEmpty(normalizedAccess)) {
            encAccess = encrypt(normalizedAccess);
            if (encAccess == null) {
                Log.e(TAG, "Failed to encrypt access token; aborting save to maintain consistency");
                return;
            }
        }

        String encRefresh = null;
        if (!TextUtils.isEmpty(normalizedRefresh)) {
            encRefresh = encrypt(normalizedRefresh);
            if (encRefresh == null) {
                Log.e(TAG, "Failed to encrypt refresh token; aborting save to maintain consistency");
                return;
            }
        }

        SharedPreferences.Editor editor = mPrefs.edit();
        if (encAccess != null) {
            editor.putString(PREF_ENC_ACCESS_TOKEN, encAccess);
        } else {
            editor.remove(PREF_ENC_ACCESS_TOKEN);
        }

        if (encRefresh != null) {
            editor.putString(PREF_ENC_REFRESH_TOKEN, encRefresh);
        } else {
            editor.remove(PREF_ENC_REFRESH_TOKEN);
        }

        long now = System.currentTimeMillis();
        long maxSeconds = (Long.MAX_VALUE - now) / 1000;
        long expiresAt = (!TextUtils.isEmpty(normalizedAccess) && expiresInSeconds > 0)
                ? now + Math.min(expiresInSeconds, maxSeconds) * 1000 : 0;
        editor.putLong(PREF_EXPIRES_AT, expiresAt);

        if (!TextUtils.isEmpty(normalizedAccess)) {
            editor.putString(PREF_AUTH_STATE, VotData.AuthState.UNVERIFIED.name());
        } else {
            editor.putString(PREF_AUTH_STATE, VotData.AuthState.ABSENT.name());
        }

        editor.commit();
    }

    public synchronized void updateTokens(@NonNull String accessToken, @Nullable String refreshToken, long expiresInSeconds) {
        String normalizedAccess = normalizeToken(accessToken);
        String normalizedRefresh = normalizeToken(refreshToken);

        String encAccess = encrypt(normalizedAccess);
        if (encAccess == null) {
            Log.e(TAG, "Failed to encrypt new access token; preserving previous credentials");
            return;
        }

        String encRefresh = null;
        if (!TextUtils.isEmpty(normalizedRefresh)) {
            encRefresh = encrypt(normalizedRefresh);
            if (encRefresh == null) {
                Log.e(TAG, "Failed to encrypt new rotated refresh token; preserving previous credentials");
                return;
            }
        }

        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putString(PREF_ENC_ACCESS_TOKEN, encAccess);

        // Only replace refresh_token if new one was successfully provided and encrypted
        if (encRefresh != null) {
            editor.putString(PREF_ENC_REFRESH_TOKEN, encRefresh);
        }

        long now = System.currentTimeMillis();
        long maxSeconds = (Long.MAX_VALUE - now) / 1000;
        long expiresAt = expiresInSeconds > 0 ? now + Math.min(expiresInSeconds, maxSeconds) * 1000 : 0;
        editor.putLong(PREF_EXPIRES_AT, expiresAt);
        editor.putString(PREF_AUTH_STATE, VotData.AuthState.UNVERIFIED.name());
        editor.commit();
    }

    public synchronized void updateAccessToken(@NonNull String accessToken, long expiresInSeconds) {
        updateTokens(accessToken, null, expiresInSeconds);
    }

    @NonNull
    public synchronized String getAccessToken() {
        String enc = mPrefs.getString(PREF_ENC_ACCESS_TOKEN, null);
        if (enc == null || enc.isEmpty()) return "";
        String plain = decrypt(enc);
        if (plain == null) {
            // Decryption failed (corrupt data or KeyStore invalidated) — fail safe
            Log.w(TAG, "Access token decryption failed, clearing corrupt entry");
            mPrefs.edit().remove(PREF_ENC_ACCESS_TOKEN).commit();
            return "";
        }
        return plain;
    }

    @NonNull
    public synchronized String getRefreshToken() {
        String enc = mPrefs.getString(PREF_ENC_REFRESH_TOKEN, null);
        if (enc == null || enc.isEmpty()) return "";
        String plain = decrypt(enc);
        if (plain == null) {
            Log.w(TAG, "Refresh token decryption failed, clearing corrupt entry");
            mPrefs.edit().remove(PREF_ENC_REFRESH_TOKEN).commit();
            return "";
        }
        return plain;
    }

    public synchronized long getExpiresAt() {
        return mPrefs.getLong(PREF_EXPIRES_AT, 0);
    }

    public synchronized boolean isExpired() {
        long expiry = getExpiresAt();
        return expiry > 0 && System.currentTimeMillis() >= expiry;
    }

    public synchronized boolean hasAccessToken() {
        return !TextUtils.isEmpty(getAccessToken());
    }

    public synchronized boolean hasRefreshToken() {
        return !TextUtils.isEmpty(getRefreshToken());
    }

    @NonNull
    public synchronized VotData.AuthState getAuthState() {
        if (!hasAccessToken()) {
            return VotData.AuthState.ABSENT;
        }
        if (isExpired() && !hasRefreshToken()) {
            return VotData.AuthState.REJECTED;
        }
        String stored = mPrefs.getString(PREF_AUTH_STATE, VotData.AuthState.UNVERIFIED.name());
        try {
            return VotData.AuthState.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return VotData.AuthState.UNVERIFIED;
        }
    }

    public synchronized void setAuthState(@NonNull VotData.AuthState state) {
        mPrefs.edit().putString(PREF_AUTH_STATE, state.name()).commit();
    }

    public synchronized void markRejected(@Nullable String rejectedToken) {
        if (hasAccessToken() && (rejectedToken == null || Helpers.equals(getAccessToken(), rejectedToken))) {
            setAuthState(VotData.AuthState.REJECTED);
        }
    }

    public synchronized void markConfirmed(@Nullable String confirmedToken) {
        if (hasAccessToken() && (confirmedToken == null || Helpers.equals(getAccessToken(), confirmedToken))) {
            setAuthState(VotData.AuthState.CONFIRMED);
        }
    }

    public synchronized void clear() {
        mPrefs.edit().clear().commit();
    }

    /**
     * Migrate plaintext tokens from legacy VotData preferences file.
     * Guaranteed atomic: plaintext is only removed after verified write.
     */
    public synchronized void migrateFromLegacyPrefs(@Nullable VotData legacyData) {
        if (legacyData == null) return;
        String legacyToken = legacyData.getRawLegacyToken();
        if (TextUtils.isEmpty(legacyToken)) return;

        if (!hasAccessToken()) {
            long legacyExpiresAt = legacyData.getRawLegacyExpiresAt();
            long remainingSeconds = legacyExpiresAt > System.currentTimeMillis()
                    ? (legacyExpiresAt - System.currentTimeMillis()) / 1000 : 0;
            saveTokens(legacyToken, null, remainingSeconds);

            // Verification check: ensure secure write succeeded
            if (Helpers.equals(getAccessToken(), legacyToken)) {
                legacyData.clearRawLegacyToken();
                Log.i(TAG, "Successfully migrated legacy plaintext OAuth token to secure store");
            } else {
                Log.w(TAG, "Migration verification failed; preserving legacy token");
            }
        } else {
            // Secure store already populated; safe to purge legacy plaintext
            legacyData.clearRawLegacyToken();
        }
    }

    // -------------------------------------------------------------------------
    // Encryption / Decryption primitives
    // -------------------------------------------------------------------------

    @Nullable
    private String encrypt(@NonNull String plainText) {
        try {
            SecretKey key = getOrCreateSecretKey();
            if (key == null) return null;

            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            byte[] iv;

            if (key.getEncoded() != null) {
                // Software / fallback key: supply random IV explicitly
                iv = new byte[GCM_IV_LENGTH_BYTES];
                mRandom.nextBytes(iv);
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            } else {
                // AndroidKeyStore key: Keystore generates IV automatically (caller-provided IVs not permitted)
                cipher.init(Cipher.ENCRYPT_MODE, key);
                iv = cipher.getIV();
            }

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            if (iv == null) {
                iv = cipher.getIV();
            }

            if (iv == null || iv.length != GCM_IV_LENGTH_BYTES) {
                Log.e(TAG, "Invalid or missing GCM IV produced by cipher");
                return null;
            }

            byte[] combined = new byte[1 + GCM_IV_LENGTH_BYTES + cipherText.length];
            combined[0] = FORMAT_VERSION;
            System.arraycopy(iv, 0, combined, 1, GCM_IV_LENGTH_BYTES);
            System.arraycopy(cipherText, 0, combined, 1 + GCM_IV_LENGTH_BYTES, cipherText.length);

            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Failed to encrypt token data: %s", e.getClass().getSimpleName());
            return null;
        }
    }

    @Nullable
    private String decrypt(@NonNull String encText) {
        try {
            byte[] combined = Base64.decode(encText, Base64.NO_WRAP);
            if (combined == null || combined.length < (1 + GCM_IV_LENGTH_BYTES + 16)) {
                return null;
            }
            byte version = combined[0];
            if (version != FORMAT_VERSION) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(combined, 1, 1 + GCM_IV_LENGTH_BYTES);
            byte[] cipherText = Arrays.copyOfRange(combined, 1 + GCM_IV_LENGTH_BYTES, combined.length);

            SecretKey key = getOrCreateSecretKey();
            if (key == null) return null;

            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherText);

            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Decryption error: %s", e.getClass().getSimpleName());
            return null;
        }
    }

    @Nullable
    private synchronized SecretKey getOrCreateSecretKey() {
        if (sTestSecretKey != null) {
            return sTestSecretKey;
        }
        if (mCachedKey != null) return mCachedKey;

        // API >= 23: ключ хранится через AndroidKeyStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !mKeyStoreFailed) {
            try {
                KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
                    if (entry instanceof KeyStore.SecretKeyEntry) {
                        mCachedKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                        return mCachedKey;
                    }
                } else {
                    KeyGenerator generator = KeyGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
                    generator.init(new KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build());
                    mCachedKey = generator.generateKey();
                    return mCachedKey;
                }
            } catch (Throwable t) {
                mKeyStoreFailed = true;
                Log.w(TAG, "AndroidKeyStore unavailable or failed: %s, falling back to app-private key", t.getClass().getSimpleName());
            }
        }

        // Fallback or API < 23: закрытый ключ приложения
        try {
            mCachedKey = getOrCreatePrivateFileKey();
            return mCachedKey;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize app-private key: %s", e.getClass().getSimpleName());
            return null;
        }
    }

    private SecretKey getOrCreatePrivateFileKey() throws IOException {
        if (mContext == null) {
            throw new IOException("Context is null");
        }
        File filesDir = mContext.getFilesDir();
        if (filesDir == null) {
            throw new IOException("Files directory is null");
        }
        File keyFile = new File(filesDir, "vot_sec_key.bin");
        if (keyFile.exists() && keyFile.length() == 32) {
            byte[] keyBytes = new byte[32];
            try (FileInputStream in = new FileInputStream(keyFile)) {
                int read = in.read(keyBytes);
                if (read == 32) {
                    return new SecretKeySpec(keyBytes, "AES");
                }
            }
        }
        byte[] newKey = new byte[32];
        mRandom.nextBytes(newKey);
        try (FileOutputStream out = new FileOutputStream(keyFile)) {
            out.write(newKey);
            out.flush();
        }
        return new SecretKeySpec(newKey, "AES");
    }

    private static String normalizeToken(String token) {
        if (token == null) return "";
        return token.trim().replaceAll("\\s+", "");
    }
}
