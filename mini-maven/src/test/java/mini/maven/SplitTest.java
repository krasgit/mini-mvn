package mini.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mini.maven.engine.ProjectSplitter;

class SplitTest {

    private static final String KAVA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="urn:mini:maven:kava" format="1">
              <name>demo</name>
              <version>1.0.0</version>
              <platforms>
                <platform id="x86_64-linux-gnu" arch="x86_64" os="linux"/>
              </platforms>
              <modules>
                <module name="lib" path="lib"/>
                <module name="app" path="app"/>
              </modules>
              <plugins>
                <plugin id="javac"/>
                <plugin id="jar"/>
              </plugins>
              <bindings>
                <binding module="lib" sources="java" plugin="javac"/>
                <binding module="app" sources="java" plugin="javac"/>
                <binding artifact="jar" plugin="jar"/>
              </bindings>
              <goals>
                <goal name="build" default="true"/>
              </goals>
            </project>
            """;

    private static final String LIB_MODULE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <module xmlns="urn:mini:maven:module" name="lib">
              <sources>
                <source language="java" dir="src/main/java"/>
              </sources>
              <dependencies>
                <dependency group="test" artifact="extra" version="1"/>
              </dependencies>
              <artifacts>
                <artifact id="mini-lib" type="jar"/>
              </artifacts>
            </module>
            """;

    private static final String APP_MODULE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <module xmlns="urn:mini:maven:module" name="app">
              <sources>
                <source language="java" dir="src/main/java"/>
              </sources>
              <dependencies>
                <dependency module="lib"/>
              </dependencies>
              <artifacts>
                <artifact id="mini-app" type="jar"/>
              </artifacts>
            </module>
            """;

    @TempDir
    Path tmp;

    @Test
    void splitsModuleAndRewritesDependencies() throws Exception {
        Path root = fixture();
        Path target = tmp.resolve("mini-app");

        ProjectSplitter.Result result = ProjectSplitter.prepare(root.resolve("kava.xml"),
                "app", "krasgit/mini-app", "public", target, "krasgit", false, false, false);

        String targetKava = Files.readString(result.targetDir().resolve("kava.xml"));
        assertTrue(targetKava.contains("<module name=\"app\" path=\"app\"/>"));
        assertFalse(targetKava.contains("name=\"lib\""));

        String targetModule = Files.readString(result.targetDir().resolve("app/module.xml"));
        assertTrue(targetModule.contains("group=\"krasgit\" artifact=\"mini-lib\" version=\"1.0.0\""));
        assertFalse(targetModule.contains("module=\"lib\""));

        assertTrue(Files.isRegularFile(result.targetDir().resolve(
                "third-party/repository/krasgit/mini-lib/1.0.0/mini-lib-1.0.0.jar")));
        assertTrue(Files.isRegularFile(result.targetDir().resolve(
                "third-party/repository/test/extra/1/extra-1.jar")));
        assertTrue(targetKava.contains("<plugin id=\"javac\"/>"));
        assertTrue(targetKava.contains("<plugin id=\"jar\"/>"));
        assertFalse(Files.exists(result.targetDir().resolve("plugins")));

        String sourceKava = Files.readString(root.resolve("kava.xml"));
        assertFalse(sourceKava.contains("<module name=\"app\""));
        assertFalse(Files.exists(root.resolve("app")));
    }

    @Test
    void dryRunDoesNotWrite() throws Exception {
        Path root = fixture();
        Path target = tmp.resolve("mini-app");

        ProjectSplitter.Result result = ProjectSplitter.prepare(root.resolve("kava.xml"),
                "app", "krasgit/mini-app", "public", target, "krasgit", false, true, false);

        assertFalse(Files.exists(target));
        assertTrue(Files.exists(root.resolve("app")));
        assertTrue(Files.readString(root.resolve("kava.xml")).equals(KAVA));
        assertFalse(result.steps().isEmpty());
    }

    private Path fixture() throws Exception {
        Path root = tmp.resolve("demo");
        Files.createDirectories(root);
        Files.writeString(root.resolve("kava.xml"), KAVA, StandardCharsets.UTF_8);
        write(root.resolve("lib/module.xml"), LIB_MODULE);
        write(root.resolve("app/module.xml"), APP_MODULE);
        write(root.resolve("app/src/main/java/App.java"), "package app; class App {}\n");
        write(root.resolve("target/lib/mini-lib.jar"), "lib-jar");
        write(root.resolve("third-party/repository/test/extra/1/extra-1.jar"), "extra-jar");
        return root;
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
