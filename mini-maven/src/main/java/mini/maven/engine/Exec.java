package mini.maven.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class Exec {

    private static final long TIMEOUT_MINUTES = 30;

    private Exec() {
    }

    public record Result(int code, String output) {
        public boolean ok() {
            return code == 0;
        }
    }

    public static Result run(List<String> command, Path directory, Map<String, String> environment,
                             boolean verbose) throws IOException {
        if (verbose) {
            System.out.println("+ " + String.join(" ", command));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        if (environment != null) builder.environment().putAll(environment);

        Process process = builder.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), bytes), "mini-mvn-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                reader.join(1000);
                return new Result(-1, "timed out after " + TIMEOUT_MINUTES + " minutes");
            }
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while running " + command.get(0), e);
        }
        return new Result(process.exitValue(), bytes.toString(StandardCharsets.UTF_8));
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (in; out) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            // The process may exit while output is still being drained.
        }
    }
}
