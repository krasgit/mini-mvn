package mini.maven.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import mini.maven.model.StateFile.FileState;

public final class Hashing {

    private Hashing() {
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String text(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String key(List<FileState> inputs, List<String> extras) {
        MessageDigest digest = digest();
        for (FileState input : inputs) {
            update(digest, input.path());
            update(digest, input.sha256());
        }
        for (String extra : extras) {
            update(digest, extra);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
