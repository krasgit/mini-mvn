package mini.maven.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
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
        boolean show = verbose || Boolean.parseBoolean(System.getenv("MINI_MVN_VERBOSE"));
        if (show) {
            System.out.println("+ " + String.join(" ", command));
        }
        boolean remote = isRemoteOperation(command);
        if (remote) {
            logRemote("+", command);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        if (environment != null) builder.environment().putAll(environment);

        Process process = builder.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), bytes, show),
                "mini-mvn-output");
        reader.setDaemon(true);
        reader.start();

        int code = -1;
        String output;
        try {
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                reader.join(1000);
                output = "timed out after " + TIMEOUT_MINUTES + " minutes";
            } else {
                reader.join(5000);
                code = process.exitValue();
                output = bytes.toString(StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            if (remote) {
                logRemote("exit=-1", command);
            }
            throw new IOException("interrupted while running " + command.get(0), e);
        }
        if (remote) {
            logRemote("exit=" + code, command);
        }
        return new Result(code, output);
    }

    private static boolean isRemoteOperation(List<String> command) {
        if (command.isEmpty()) return false;
        String executable = Path.of(command.get(0)).getFileName().toString();
        if (executable.equals("gh") || executable.equals("git")) return true;
        if (!executable.equals("curl")) return false;
        return command.stream().anyMatch(arg -> arg.contains("github.com"));
    }

    private static void logRemote(String event, List<String> command) {
        String line = "[git] " + Instant.now() + " " + event + " " + String.join(" ", command);
        System.out.println(line);
        String configured = System.getenv("MINI_MVN_GIT_LOG");
        if (configured == null || configured.isBlank()) return;
        try {
            Path path = Path.of(configured);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, boolean echo) {
        try (in; out) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                    if (echo) {
                        System.out.write(buffer, 0, read);
                        System.out.flush();
                    }
                }
            }
        } catch (IOException ignored) {
            // The process may exit while output is still being drained.
        }
    }
}
