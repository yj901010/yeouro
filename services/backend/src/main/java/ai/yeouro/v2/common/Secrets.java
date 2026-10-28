package ai.yeouro.v2.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

public final class Secrets {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Secrets() {}

  public static String token() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
