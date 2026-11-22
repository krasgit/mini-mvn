package mini.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mini.maven.model.RioFile;

class RioTest {

    private static final String RIO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rio xmlns="urn:mini:rio:project" format="1">
              <name>mini-rio</name>
              <repository>krasgit/mini-rio</repository>
              <ref>main</ref>
              <run main="rio.server.HttpServer" browser="rio-browser" port="8099" workspace="."/>
              <platforms>
                <platform name="default" runtime="java"/>
                <platform name="arm" runtime="native" target="aarch64" cc="gcc"/>
                <platform name="x86" runtime="native" target="x86-64"/>
              </platforms>
              <profiles>
                <profile name="debug" runtime="java" noOpt="true"/>
                <profile name="release" runtime="java"/>
                <profile name="static" runtime="native" static="true" gc="marksweep"/>
                <profile name="dynamic" runtime="native" gc="marksweep"/>
              </profiles>
              <dependencies>
                <dependency name="mini-compiler" repository="krasgit/mini-compiler" ref="main" kind="kava-dist"/>
              </dependencies>
            </rio>
            """;

    @TempDir
    Path tmp;

    @Test
    void parsesRioDescriptor() throws Exception {
        Path file = tmp.resolve("rio.xml");
        Files.writeString(file, RIO);
        RioFile rio = RioFile.load(file);

        assertEquals("mini-rio", rio.name());
        assertEquals("java", rio.defaultRuntime());
        assertEquals("default", rio.defaultPlatform());
        assertEquals("8099", rio.run().port());
        assertEquals(3, rio.platforms().size());
        assertEquals(1, rio.dependencies().size());
    }

    @Test
    void resolvesJavaByDefault() throws Exception {
        Path file = tmp.resolve("rio.xml");
        Files.writeString(file, RIO);
        RioFile.Resolution resolution = RioFile.load(file).resolve(null, null);

        assertEquals("java", resolution.runtime());
        assertFalse(resolution.staticLink());
    }

    @Test
    void resolvesNativeArmStatic() throws Exception {
        Path file = tmp.resolve("rio.xml");
        Files.writeString(file, RIO);
        RioFile.Resolution resolution = RioFile.load(file).resolve("arm", "static");

        assertEquals("native", resolution.runtime());
        assertEquals("aarch64", resolution.target());
        assertEquals("gcc", resolution.cc());
        assertTrue(resolution.staticLink());
    }

    @Test
    void resolvesNativeX86Dynamic() throws Exception {
        Path file = tmp.resolve("rio.xml");
        Files.writeString(file, RIO);
        RioFile.Resolution resolution = RioFile.load(file).resolve("x86", "dynamic");

        assertEquals("native", resolution.runtime());
        assertEquals("x86-64", resolution.target());
        assertFalse(resolution.staticLink());
    }

    @Test
    void rejectsStaticOnJava() throws Exception {
        Path file = tmp.resolve("rio.xml");
        Files.writeString(file, RIO);
        RioFile rio = RioFile.load(file);

        assertThrows(IllegalArgumentException.class, () -> rio.resolve("default", "static"));
    }

    @Test
    void parsesRunOptions() {
        Main.RunOptions options = Main.RunOptions.parse(new String[] {
                "run", "mini-rio", "-platform", "arm", "-profile", "static",
                "--port", "8100", "--", "--heap", "64m"
        });

        assertEquals("mini-rio", options.spec);
        assertEquals("arm", options.platform);
        assertEquals("static", options.profile);
        assertEquals("8100", options.port);
        assertEquals(2, options.programArgs.size());
    }
}
