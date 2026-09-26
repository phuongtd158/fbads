package com.fbads.importer;

import org.bouncycastle.crypto.generators.SCrypt;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Định dạng dữ liệu bản Node lưu trên Upstash (lib/remote.js): 'v1:' + base64(iv 12 | tag 16 | gzip(JSON) đã mã hoá AES-256-GCM).
 * Khoá = scrypt(DATA_KEY, 'fbads.data.v1', 32) với tham số mặc định của Node (N=16384, r=8, p=1).
 */
public final class UpstashCodec {
    static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private UpstashCodec() {}

    static byte[] key(String secret) {
        return SCrypt.generate(secret.getBytes(StandardCharsets.UTF_8), "fbads.data.v1".getBytes(StandardCharsets.UTF_8), 16384, 8, 1, 32);
    }

    public static String decode(String text, String secret) {
        if (text == null || !text.startsWith(PREFIX))
            throw new IllegalStateException("Dữ liệu trên Upstash không đúng định dạng của tool này (khoá fbads:data bị ghi bởi chương trình khác?).");
        byte[] buf = Base64.getDecoder().decode(text.substring(PREFIX.length()));
        if (buf.length < 29) throw new IllegalStateException("Dữ liệu trên Upstash bị cắt cụt hoặc hỏng.");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(secret), "AES"), new GCMParameterSpec(128, buf, 0, 12));
            // Java muốn tag nằm cuối bản mã, Node để tag ngay sau iv → ghép lại: dữ liệu | tag
            byte[] body = new byte[buf.length - 12];
            System.arraycopy(buf, 28, body, 0, buf.length - 28);
            System.arraycopy(buf, 12, body, buf.length - 28, 16);
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(c.doFinal(body)))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Không giải mã được dữ liệu trên Upstash: DATA_KEY sai hoặc dữ liệu bị hỏng.", e);
        }
    }

    /** Chiều ngược lại (dùng trong test để kiểm tra khớp định dạng với bản Node) */
    public static String encode(String json, String secret) {
        try {
            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(zipped)) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(secret), "AES"), new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(zipped.toByteArray()); // dữ liệu | tag
            int n = enc.length - 16;
            byte[] outBuf = new byte[12 + 16 + n];
            System.arraycopy(iv, 0, outBuf, 0, 12);
            System.arraycopy(enc, n, outBuf, 12, 16);
            System.arraycopy(enc, 0, outBuf, 28, n);
            return PREFIX + Base64.getEncoder().encodeToString(outBuf);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
