package anomaly.hex.android.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed secret storage using AndroidKeyStore AES-256-GCM.
 * Never logs or exports plaintext; encrypted blobs live in private hex_secrets prefs
 * excluded from cloud backup and device-to-device transfer.
 */
public final class SecretStore {
  private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
  private static final String KEY_ALIAS = "hex_byok_aes_gcm_v1";
  private static final String PREFS_NAME = "hex_secrets";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;

  private SecretStore() {}

  public static String get(Context context, String name) {
    if (context == null || name == null || name.isEmpty()) return "";
    SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    String packed = prefs.getString(name, "");
    if (packed == null || packed.isEmpty()) return "";
    int sep = packed.indexOf(':');
    if (sep <= 0 || sep >= packed.length() - 1) return "";
    try {
      byte[] iv = Base64.decode(packed.substring(0, sep), Base64.NO_WRAP);
      byte[] ciphertext = Base64.decode(packed.substring(sep + 1), Base64.NO_WRAP);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] plain = cipher.doFinal(ciphertext);
      return new String(plain, StandardCharsets.UTF_8).trim();
    } catch (Exception e) {
      return "";
    }
  }

  public static boolean put(Context context, String name, String plaintext) {
    if (context == null || name == null || name.isEmpty()) return false;
    String value = plaintext == null ? "" : plaintext.trim();
    if (value.isEmpty()) {
      remove(context, name);
      return true;
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
      byte[] iv = cipher.getIV();
      byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      String packed = Base64.encodeToString(iv, Base64.NO_WRAP)
          + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
      context.getApplicationContext()
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putString(name, packed)
          .apply();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static void remove(Context context, String name) {
    if (context == null || name == null || name.isEmpty()) return;
    context.getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(name)
        .apply();
  }

  /** One-time migration from legacy plaintext SharedPreferences into Keystore-encrypted storage. */
  public static void migrateFromPlaintext(Context context, String legacyPrefs, String legacyKey, String targetKey) {
    if (context == null) return;
    SharedPreferences old = context.getApplicationContext().getSharedPreferences(legacyPrefs, Context.MODE_PRIVATE);
    String legacy = old.getString(legacyKey, "");
    if (legacy == null || legacy.trim().isEmpty()) return;
    if (get(context, targetKey).isEmpty()) {
      if (put(context, targetKey, legacy.trim())) {
        old.edit().remove(legacyKey).apply();
      }
    } else {
      old.edit().remove(legacyKey).apply();
    }
  }

  /** Returns a safe masked preview (e.g. AIza••••3x9Q) without exposing the secret. */
  public static String mask(String secret) {
    if (secret == null) return "";
    String s = secret.trim();
    if (s.isEmpty()) return "";
    if (s.length() <= 8) return "••••";
    return s.substring(0, 4) + "••••" + s.substring(s.length() - 4);
  }

  private static synchronized SecretKey getOrCreateKey() throws Exception {
    KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
    ks.load(null);
    KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
    if (entry instanceof KeyStore.SecretKeyEntry sk) {
      return sk.getSecretKey();
    }
    KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
    KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build();
    gen.init(spec);
    return gen.generateKey();
  }
}
