package mini.maven.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PomWriter {

    private PomWriter() {
    }

    public static void write(Path file, String group, String artifact, String version)
            throws IOException {
        Files.createDirectories(file.getParent());
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>" + escape(group) + "</groupId>\n"
                + "  <artifactId>" + escape(artifact) + "</artifactId>\n"
                + "  <version>" + escape(version) + "</version>\n"
                + "</project>\n";
        Files.writeString(file, xml, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
