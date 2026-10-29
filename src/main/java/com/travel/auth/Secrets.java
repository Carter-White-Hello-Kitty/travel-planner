package com.travel.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class Secrets {
  final SecureRandom random = new SecureRandom();
  final RSAPrivateKey privateKey;
  final RSAPublicKey publicKey;
  private final byte[] mailKey, rateKey;

  public Secrets(Environment env) throws Exception {
    boolean dev = env.getProperty("auth.development-keys", Boolean.class, false);
    String priv = env.getProperty("auth.private-key", "");
    if (priv.isBlank()) {
      if (!dev)
        throw new IllegalStateException("Configure JWT keys or explicitly use the local profile");
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      KeyPair p = g.generateKeyPair();
      privateKey = (RSAPrivateKey) p.getPrivate();
      publicKey = (RSAPublicKey) p.getPublic();
    } else {
      KeyFactory k = KeyFactory.getInstance("RSA");
      privateKey = (RSAPrivateKey) k.generatePrivate(new PKCS8EncodedKeySpec(pem(priv)));
      publicKey =
          (RSAPublicKey)
              k.generatePublic(
                  new X509EncodedKeySpec(pem(env.getRequiredProperty("auth.public-key"))));
    }
    if (publicKey.getModulus().bitLength() < 2048
        || !privateKey.getModulus().equals(publicKey.getModulus()))
      throw new IllegalArgumentException("RSA keys must match and be at least 2048 bits");
    mailKey = key(env, "auth.mail-key", dev);
    rateKey = key(env, "auth.rate-key", dev);
  }

  private byte[] key(Environment e, String n, boolean dev) {
    String v = e.getProperty(n, "");
    byte[] b;
    if (v.isBlank() && dev) {
      b = new byte[32];
      random.nextBytes(b);
    } else b = Base64.getDecoder().decode(v);
    if (b.length != 32)
      throw new IllegalArgumentException(n + " must be base64 of 32 random bytes");
    return b;
  }

  private byte[] pem(String path) throws Exception {
    return Base64.getDecoder()
        .decode(
            Files.readString(Path.of(path))
                .replaceAll("-----[^-]+-----", "")
                .replaceAll("\\s", ""));
  }

  public String token() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public String hash(String v) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String rateHash(String v) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(rateKey, "HmacSHA256"));
      return HexFormat.of().formatHex(m.doFinal(v.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public byte[] encrypt(String s) {
    try {
      byte[] n = new byte[12];
      random.nextBytes(n);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(mailKey, "AES"), new GCMParameterSpec(128, n));
      byte[] v = c.doFinal(s.getBytes(StandardCharsets.UTF_8)), out = new byte[n.length + v.length];
      System.arraycopy(n, 0, out, 0, 12);
      System.arraycopy(v, 0, out, 12, v.length);
      return out;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String decrypt(byte[] v) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(mailKey, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(v, 12)));
      return new String(c.doFinal(Arrays.copyOfRange(v, 12, v.length)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
