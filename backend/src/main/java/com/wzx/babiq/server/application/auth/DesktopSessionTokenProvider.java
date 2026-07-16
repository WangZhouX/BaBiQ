package com.wzx.babiq.server.application.auth;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.application.config.BusinessBackendInstanceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 单次消费桌面父进程提供的本机会话 Token，并仅保留不可逆摘要。
 *
 * <p>威胁边界：Token 文件与运行目录依赖 owner-only 权限。与 {@code LocalKeyStoreSecretStore}
 * 一致，本组件不试图防御同一 OS 账号下能够篡改该目录或读取进程的恶意进程。当前实现防止
 * Token 在日志或响应中泄漏、拒绝已存在的 link/reparse 路径、避免打开后的路径替换导致误删，
 * 并通过句柄级 {@code DELETE_ON_CLOSE} 消费已打开的原始 Token 对象。</p>
 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class DesktopSessionTokenProvider {

    private static final int MAX_TOKEN_BYTES = 512;

    private final byte[] tokenDigest;

    @Autowired
    public DesktopSessionTokenProvider(BusinessDesktopModeProperties properties,
                                       BusinessBackendInstanceLock instanceLock) {
        this(properties.sessionTokenFile());
    }

    public DesktopSessionTokenProvider(Path tokenFile) {
        this(TokenFileClaim.acquire(tokenFile.toAbsolutePath().normalize()));
    }

    DesktopSessionTokenProvider(TokenFileClaim tokenFileClaim) {
        this.tokenDigest = consumeDigest(tokenFileClaim);
    }

    public boolean matches(String candidate) {
        if (!isValidToken(candidate)) {
            return false;
        }
        byte[] candidateDigest = digest(candidate.getBytes(StandardCharsets.US_ASCII));
        return MessageDigest.isEqual(tokenDigest, candidateDigest);
    }

    @Override
    public String toString() {
        return "DesktopSessionTokenProvider(tokenDigest=[REDACTED])";
    }

    private static byte[] consumeDigest(TokenFileClaim tokenFileClaim) {
        Throwable primaryFailure = null;
        try {
            byte[] tokenBytes = tokenFileClaim.readBounded(MAX_TOKEN_BYTES);
            String token = new String(tokenBytes, StandardCharsets.US_ASCII);
            if (!isValidToken(token)) {
                throw invalidTokenFile();
            }
            return digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException | Error exception) {
            primaryFailure = exception;
            throw exception;
        } catch (IOException exception) {
            IllegalStateException unavailable =
                    new IllegalStateException("business desktop session token is unavailable");
            primaryFailure = unavailable;
            throw unavailable;
        } finally {
            try {
                tokenFileClaim.close();
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isValidToken(String token) {
        if (token == null || token.length() != 43) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            boolean valid = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-';
            if (!valid) {
                return false;
            }
        }
        try {
            return Base64.getUrlDecoder().decode(token).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static IllegalStateException invalidTokenFile() {
        return new IllegalStateException("business desktop session token is invalid");
    }

}

/**
 * 持有与原始 Token 对象绑定的句柄，并由 {@code DELETE_ON_CLOSE} 完成单次消费。
 *
 * <p>安全性依赖运行目录的 owner-only 权限，不承诺防御同一 OS 账号下的恶意进程。</p>
 */
final class TokenFileClaim implements AutoCloseable {

    private final ClaimChannel channel;
    private boolean channelClosed;

    TokenFileClaim(ClaimChannel channel) {
        this.channel = channel;
    }

    static TokenFileClaim acquire(Path tokenFile) {
        return acquire(tokenFile, path -> new FileChannelClaimChannel(FileChannel.open(
                path,
                StandardOpenOption.READ,
                StandardOpenOption.DELETE_ON_CLOSE,
                LinkOption.NOFOLLOW_LINKS)));
    }

    static TokenFileClaim acquire(Path tokenFile, TokenChannelOpener opener) {
        try {
            rejectLinksInExistingPath(tokenFile);
            BasicFileAttributes attributes = Files.readAttributes(
                    tokenFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw invalidTokenFile();
            }
            return new TokenFileClaim(opener.open(tokenFile));
        } catch (UnsupportedOperationException exception) {
            throw new IllegalStateException("business desktop session token is unavailable");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("business desktop session token is unavailable");
        }
    }

    byte[] readBounded(int maxBytes) throws IOException {
        return channel.readBounded(maxBytes);
    }

    @Override
    public synchronized void close() {
        if (channelClosed) {
            return;
        }
        IllegalStateException cleanupFailure = cleanupFailure();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                channel.close();
                channelClosed = true;
                return;
            } catch (Exception exception) {
                cleanupFailure.addSuppressed(exception);
            }
        }
        throw cleanupFailure;
    }

    private static IllegalStateException cleanupFailure() {
        return new IllegalStateException("business desktop session token could not be deleted");
    }

    private static IllegalStateException invalidTokenFile() {
        return new IllegalStateException("business desktop session token is invalid");
    }

    private static void rejectLinksInExistingPath(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current == null ? segment : current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("missing token path");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(current) || attributes.isOther()) {
                throw new IllegalArgumentException("business desktop session token path must not contain a link");
            }
        }
    }

}

interface ClaimChannel extends AutoCloseable {

    byte[] readBounded(int maxBytes) throws IOException;

    @Override
    void close() throws Exception;
}

@FunctionalInterface
interface TokenChannelOpener {

    ClaimChannel open(Path path) throws IOException;
}

final class FileChannelClaimChannel implements ClaimChannel {

    private final FileChannel channel;

    FileChannelClaimChannel(FileChannel channel) {
        this.channel = channel;
    }

    @Override
    public byte[] readBounded(int maxBytes) throws IOException {
        channel.position(0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(Math.min(256, maxBytes + 1));
        int total = 0;
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            int count = buffer.remaining();
            total += count;
            if (total > maxBytes) {
                throw new IllegalStateException("business desktop session token is invalid");
            }
            output.write(buffer.array(), buffer.position(), count);
            buffer.clear();
        }
        return output.toByteArray();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
