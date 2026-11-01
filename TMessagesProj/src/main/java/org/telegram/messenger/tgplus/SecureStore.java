package org.telegram.messenger.tgplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small secrets (the Gemini API key) encrypted with an AES-256-GCM key held in the
 * Android Keystore. The ciphertext lives in private prefs that are excluded from backup;
 * the Keystore key never leaves the device, so a restored copy could not be decrypted anyway.
 */
public final class SecureStore {

    private static final String PREFS = "tgplus_secure";
    private static final String KEY_ALIAS = "tgplus_secure_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final HashMap<String, String> cache = new HashMap<>();

    private SecureStore() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            keyStore.deleteEntry(KEY_ALIAS);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }

    public static synchronized boolean has(String name) {
        return cache.containsKey(name) || prefs().contains(name);
    }

    /** Stores the value; an empty or null value removes it. Returns false if encryption failed. */
    public static synchronized boolean put(String name, String value) {
        if (value == null || value.isEmpty()) {
            remove(name);
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String stored = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs().edit().putString(name, stored).apply();
            cache.put(name, value);
            return true;
        } catch (Exception e) {
            FileLog.e("tgplus secure store: encryption failed");
            return false;
        }
    }

    /** Returns the value, or null if absent or no longer decryptable (the stale value is then removed). */
    public static synchronized String get(String name) {
        String cached = cache.get(name);
        if (cached != null) return cached;
        String stored = prefs().getString(name, null);
        if (stored == null) return null;
        try {
            int split = stored.indexOf(':');
            byte[] iv = Base64.decode(stored.substring(0, split), Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(stored.substring(split + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            String value = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            cache.put(name, value);
            return value;
        } catch (Exception e) {
            FileLog.e("tgplus secure store: stored value could not be decrypted and was removed");
            prefs().edit().remove(name).apply();
            return null;
        }
    }

    public static synchronized void remove(String name) {
        cache.remove(name);
        prefs().edit().remove(name).apply();
    }

    /** For display only: the last four characters, e.g. "…a1B2". */
    public static String hint(String value) {
        if (value == null || value.length() < 8) return "…";
        return "…" + value.substring(value.length() - 4);
    }
}
