package ai.yeouro.v2.sharing;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Secrets are returned once. Only SHA-256 digests of 256-bit random values are persisted. */
public final class Secrets {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Secrets() {}

  public static String issue() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static boolean matches(String raw, byte[] digest) {
    return raw != null && MessageDigest.isEqual(hash(raw), digest);
  }
}
