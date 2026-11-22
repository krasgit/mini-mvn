package mini.maven.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HashManifest {

    private HashManifest() {
    }

    public static Path write(Path root, List<Path> files, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(path -> key(root, path)));
        StringBuilder out = new StringBuilder();
        for (Path file : sorted) {
            if (!Files.isRegularFile(file)) continue;
            out.append(key(root, file)).append(' ').append(Hashing.sha256(file)).append('\n');
        }
        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
        return output;
    }

    public static Map<String, String> read(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] fields = trimmed.split("\\s+");
            if (fields.length != 2) {
                throw new IOException(file + ": invalid hash line '" + line + "'");
            }
            entries.put(fields[0], fields[1]);
        }
        return entries;
    }

    public static boolean compare(Path a, Path b, StringBuilder report) throws IOException {
        Map<String, String> left = read(resolveManifest(a));
        Map<String, String> right = read(resolveManifest(b));
        boolean same = left.equals(right);
        List<String> keys = new ArrayList<>(left.keySet());
        for (String key : right.keySet()) {
            if (!keys.contains(key)) keys.add(key);
        }
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            String leftHash = left.get(key);
            String rightHash = right.get(key);
            if (leftHash == null) report.append("+ ").append(key).append('\n');
            else if (rightHash == null) report.append("- ").append(key).append('\n');
            else if (!leftHash.equals(rightHash)) {
                report.append("~ ").append(key).append(' ')
                        .append(leftHash.substring(0, Math.min(12, leftHash.length())))
                        .append(" -> ")
                        .append(rightHash.substring(0, Math.min(12, rightHash.length())))
                        .append('\n');
            }
        }
        return same;
    }

    private static Path resolveManifest(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Path manifest = path.resolve("target").resolve(".mini-maven")
                    .resolve("artifacts.sha256");
            if (!Files.isRegularFile(manifest)) {
                throw new IOException("no hash manifest in " + path);
            }
            return manifest;
        }
        if (!Files.isRegularFile(path)) throw new IOException("no such manifest: " + path);
        return path;
    }

    private static String key(Path root, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try {
            return root.toAbsolutePath().normalize().relativize(absolute).toString()
                    .replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return absolute.toString().replace('\\', '/');
        }
    }
}
