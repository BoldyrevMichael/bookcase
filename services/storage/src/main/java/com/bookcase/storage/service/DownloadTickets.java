package com.bookcase.storage.service;

import com.bookcase.storage.config.StorageProperties;
import com.bookcase.storage.dto.DownloadTicket;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Подписанные пропуска на скачивание.
 *
 * <p>Нужны там, где ссылку нельзя снабдить заголовком: адрес архива, который отдают пользователю
 * после фоновой сборки, живёт своей жизнью и открывается обычным переходом по ссылке. Пропуск
 * говорит ровно одно: такому-то владельцу до такого-то момента разрешено скачать такой-то файл.
 *
 * <p>Ключ известен только этому сервису — он же и выписывает, и проверяет. Подпись сверяется за
 * постоянное время: сравнение, обрывающееся на первом несовпавшем байте, подсказывает подбирающему,
 * насколько он близок.
 */
@Service
public class DownloadTickets {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final java.time.Duration lifetime;

    public DownloadTickets(StorageProperties properties) {
        this.secret = properties.downloadTokenSecret().getBytes(StandardCharsets.UTF_8);
        this.lifetime = properties.downloadTokenTtl();
    }

    public DownloadTicket issue(String sha256, String ownerId) {
        Instant expiresAt = Instant.now().plus(lifetime);
        String payload = payload(sha256, ownerId, expiresAt.getEpochSecond());
        String token =
                ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                        + "."
                        + ENCODER.encodeToString(sign(payload));
        return new DownloadTicket(token, expiresAt);
    }

    /**
     * Разбирает пропуск.
     *
     * @return владелец, которому он выписан, если пропуск подлинный, не просрочен и выписан именно
     *     на этот файл
     */
    public Optional<String> verify(String token, String sha256) {
        int separator = token.lastIndexOf('.');
        if (separator < 0) {
            return Optional.empty();
        }
        String payload;
        byte[] signature;
        try {
            payload =
                    new String(
                            DECODER.decode(token.substring(0, separator)), StandardCharsets.UTF_8);
            signature = DECODER.decode(token.substring(separator + 1));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        if (!java.security.MessageDigest.isEqual(sign(payload), signature)) {
            return Optional.empty();
        }
        String[] parts = payload.split(":", 3);
        if (parts.length != 3 || !constantTimeEquals(parts[0], sha256)) {
            return Optional.empty();
        }
        if (Instant.now().getEpochSecond() > Long.parseLong(parts[2])) {
            return Optional.empty();
        }
        return Optional.of(parts[1]);
    }

    private static boolean constantTimeEquals(String first, String second) {
        return java.security.MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(String sha256, String ownerId, long expiresAtEpochSecond) {
        return sha256 + ":" + ownerId + ":" + expiresAtEpochSecond;
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException(
                    "не удалось подписать пропуск на скачивание", exception);
        }
    }
}
