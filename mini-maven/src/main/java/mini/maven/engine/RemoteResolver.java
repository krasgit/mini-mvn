package mini.maven.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mini.maven.model.RioFile;

public final class RemoteResolver {

    public record Resolved(Path dir, RioFile descriptor, String sha, String repository) {
    }

    public record Spec(String owner, String repo, String ref) {
        public String repository() {
            return owner + "/" + repo;
        }
    }

    public static Spec parseSpec(String raw) {
        String value = raw == null || raw.isBlank() ? "mini-rio" : raw.trim();
        if (value.startsWith("github:")) value = value.substring("github:".length());
        String ref = "main";
        int at = value.lastIndexOf('@');
        if (at > 0) {
            ref = value.substring(at + 1);
            value = value.substring(0, at);
        }
        String[] parts = value.split("/");
        if (parts.length == 1) parts = new String[] { "krasgit", parts[0] };
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("invalid GitHub project '" + raw
                    + "'; expected mini-rio, owner/repo, or github:owner/repo@ref");
        }
        for (String part : parts) {
            if (!part.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("invalid GitHub project part '" + part + "'");
            }
        }
        return new Spec(parts[0], parts[1], ref);
    }

    public static Resolved resolve(String raw, boolean verbose) throws IOException {
        Spec spec = parseSpec(raw);
        Path cache = cacheRoot();
        Path descriptorDir = cache.resolve("descriptors").resolve("github.com")
                .resolve(spec.owner()).resolve(spec.repo()).resolve(safeRef(spec.ref()));
        Path descriptorFile = descriptorDir.resolve("rio.xml");
        Path shaFile = descriptorDir.resolve("sha.txt");

        boolean offline = Boolean.parseBoolean(System.getenv("MINI_MVN_OFFLINE"));
        String sha;
        if (offline && Files.isRegularFile(descriptorFile) && Files.isRegularFile(shaFile)) {
            sha = Files.readString(shaFile, StandardCharsets.UTF_8).trim();
        } else {
            Files.createDirectories(descriptorDir);
            String content = fetchDescriptor(spec, verbose);
            sha = fetchSha(spec, verbose);
            Files.writeString(descriptorFile, content, StandardCharsets.UTF_8);
            Files.writeString(shaFile, sha, StandardCharsets.UTF_8);
        }
        if (sha.isEmpty()) throw new IOException("empty commit SHA for " + spec.repository());

        Path sources = cache.resolve("sources").resolve("github.com")
                .resolve(spec.owner()).resolve(spec.repo()).resolve(sha);
        materialize(spec, sha, sources, verbose);
        Path descriptor = sources.resolve("rio.xml");
        if (!Files.isRegularFile(descriptor)) {
            throw new IOException(spec.repository() + "@" + sha + " has no rio.xml");
        }
        RioFile rio = RioFile.load(descriptor);
        return new Resolved(sources, rio, sha, spec.repository());
    }

    public static Resolved resolveSource(String raw, boolean verbose) throws IOException {
        Spec spec = parseSpec(raw);
        String sha = fetchSha(spec, verbose);
        Path sources = cacheRoot().resolve("sources").resolve("github.com")
                .resolve(spec.owner()).resolve(spec.repo()).resolve(sha);
        materialize(spec, sha, sources, verbose);
        return new Resolved(sources, null, sha, spec.repository());
    }

    public static Path cacheRoot() {
        String configured = System.getenv("MINI_MVN_CACHE");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        String home = System.getProperty("user.home");
        return Path.of(home, ".cache", "mini-mvn").toAbsolutePath().normalize();
    }

    private static String fetchDescriptor(Spec spec, boolean verbose) throws IOException {
        List<String> gh = List.of("gh", "api", "-H", "Accept: application/vnd.github.raw",
                "repos/" + spec.repository() + "/contents/rio.xml?ref=" + spec.ref());
        Exec.Result result = Exec.run(gh, Path.of("."), null, verbose);
        if (result.ok() && !result.output().isBlank()) return result.output();

        List<String> curl = List.of("curl", "-fsSL", "-H", "Accept: application/vnd.github.raw",
                "https://api.github.com/repos/" + spec.repository()
                        + "/contents/rio.xml?ref=" + spec.ref());
        Exec.Result fallback = Exec.run(curl, Path.of("."), null, verbose);
        if (!fallback.ok()) {
            throw new IOException("cannot fetch rio.xml from " + spec.repository()
                    + ": " + fallback.output().trim());
        }
        return fallback.output();
    }

    private static String fetchSha(Spec spec, boolean verbose) throws IOException {
        List<String> gh = List.of("gh", "api", "repos/" + spec.repository()
                + "/commits/" + spec.ref(), "--jq", ".sha");
        Exec.Result result = Exec.run(gh, Path.of("."), null, verbose);
        if (result.ok()) {
            String sha = result.output().trim();
            if (!sha.isEmpty()) return sha;
        }

        List<String> git = List.of("git", "ls-remote",
                "https://github.com/" + spec.repository() + ".git",
                "refs/heads/" + spec.ref());
        Exec.Result fallback = Exec.run(git, Path.of("."), null, verbose);
        if (!fallback.ok()) {
            throw new IOException("cannot resolve commit for " + spec.repository()
                    + ": " + fallback.output().trim());
        }
        for (String line : fallback.output().lines().toList()) {
            String[] fields = line.split("\\s+");
            if (fields.length >= 1 && !fields[0].isBlank()) return fields[0];
        }
        throw new IOException("no commit found for " + spec.repository() + "@" + spec.ref());
    }

    private static void materialize(Spec spec, String sha, Path dir, boolean verbose)
            throws IOException {
        Path marker = dir.resolve(".mini-mvn-source");
        if (Files.isRegularFile(marker)
                && Files.readString(marker, StandardCharsets.UTF_8).trim().equals(sha)
                && Files.isRegularFile(dir.resolve("rio.xml"))) {
            return;
        }
        if (Files.exists(dir)) delete(dir);
        Files.createDirectories(dir);
        String url = "https://github.com/" + spec.repository() + ".git";
        run(List.of("git", "init", "-q"), dir, verbose);
        run(List.of("git", "remote", "add", "origin", url), dir, verbose);
        run(List.of("git", "fetch", "--depth", "1", "origin", sha), dir, verbose);
        run(List.of("git", "checkout", "-q", "FETCH_HEAD"), dir, verbose);
        Files.writeString(marker, sha, StandardCharsets.UTF_8);
    }

    private static void run(List<String> command, Path dir, boolean verbose) throws IOException {
        Map<String, String> env = Map.of(
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "safe.directory",
                "GIT_CONFIG_VALUE_0", dir.toAbsolutePath().normalize().toString());
        Exec.Result result = Exec.run(command, dir, env, verbose);
        if (!result.ok()) {
            throw new IOException(String.join(" ", command) + " failed:\n" + result.output());
        }
    }

    private static String safeRef(String ref) {
        return ref.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) delete(entry);
            }
        }
        Files.deleteIfExists(path);
    }
}
