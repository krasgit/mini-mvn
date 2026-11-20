package mini.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mini.maven.model.KavaFile;
import mini.maven.model.ModuleFile;
import mini.maven.model.PluginFile;
import mini.maven.model.StateFile;
import mini.maven.xml.XmlException;

class XmlTest {

    private static final String KAVA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="urn:mini:maven:kava" format="1">
              <name>demo</name>
              <version>1.0</version>
              <platforms>
                <platform id="x86_64-linux-gnu" arch="x86_64" os="linux"/>
              </platforms>
              <modules>
                <module name="compiler" path="compiler"/>
              </modules>
              <plugins>
                <plugin id="javac" path="plugins/javac"/>
              </plugins>
              <bindings>
                <binding module="compiler" sources="java" plugin="javac"/>
              </bindings>
              <goals>
                <goal name="build" default="true"/>
              </goals>
            </project>
            """;

    @TempDir
    Path tmp;

    private Path write(String relative, String text) throws Exception {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void parsesAProject() throws Exception {
        Path file = write("kava.xml", KAVA);
        KavaFile project = KavaFile.load(file);

        assertEquals("demo", project.name());
        assertEquals("1.0", project.version());
        assertEquals(1, project.platforms().size());
        assertEquals("x86_64-linux-gnu", project.platforms().get(0).id());
        assertEquals(1, project.modules().size());
        assertEquals("compiler", project.modules().get(0).name());
        assertEquals("javac", project.bindings().get(0).plugin());
        assertTrue(project.defaultGoal().isDefault());
    }

    @Test
    void rejectsDoctype() throws Exception {
        Path file = write("bad.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY x "boom">]>
                <project xmlns="urn:mini:maven:kava" format="1"/>
                """);
        assertThrows(XmlException.class, () -> KavaFile.load(file));
    }

    @Test
    void rejectsUnknownElements() throws Exception {
        Path file = write("bad.xml", KAVA.replace("<goals>", "<nonsense/><goals>"));
        XmlException e = assertThrows(XmlException.class, () -> KavaFile.load(file));
        assertTrue(e.getMessage().contains("unexpected element <nonsense>"), e.getMessage());
    }

    @Test
    void rejectsDuplicatePlatforms() throws Exception {
        Path file = write("bad.xml", KAVA.replace("</platforms>", """
                <platform id="x86_64-linux-gnu" arch="x86_64" os="linux"/>
                </platforms>"""));
        XmlException e = assertThrows(XmlException.class, () -> KavaFile.load(file));
        assertTrue(e.getMessage().contains("duplicate platform"), e.getMessage());
    }

    @Test
    void parsesModuleAndSuites() throws Exception {
        Path file = write("compiler/module.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <module xmlns="urn:mini:maven:module" name="compiler">
                  <sources><source language="java" dir="src/main/java"/></sources>
                  <testSources><source language="java" dir="src/test/java"/></testSources>
                  <dependencies>
                    <dependency group="org.eclipse.jdt" artifact="eclipse.jdt.core" version="3.42.0"/>
                    <dependency module="runtime-native" scope="runtime"/>
                  </dependencies>
                  <artifacts>
                    <artifact id="mini-compiler" type="jar" mainClass="mini.MiniCompiler"/>
                    <artifact id="tests" type="jar" tests="true"/>
                  </artifacts>
                  <tests>
                    <suite name="smoke"><include class="mini.compiler.SmokeTest"/></suite>
                    <suite name="full"><include package="mini"/><exclude class="mini.Hidden"/></suite>
                  </tests>
                </module>
                """);
        ModuleFile module = ModuleFile.load(file);

        assertEquals("compiler", module.name());
        assertEquals(1, module.sources("java").size());
        assertTrue(module.sources("java").get(0).dir().endsWith("src/main/java"));
        assertEquals(2, module.dependencies().size());
        assertTrue(module.dependencies().get(0).coordinates().contains("eclipse.jdt.core"));
        assertTrue(module.dependencies().get(1).isModule());
        assertTrue(module.artifacts().get(1).tests());

        ModuleFile.Suite smoke = module.suite("smoke");
        assertTrue(smoke.contains("mini.compiler.SmokeTest"));
        assertFalse(smoke.contains("mini.compiler.ExeTest"));

        ModuleFile.Suite full = module.suite("full");
        assertTrue(full.contains("mini.compiler.ExeTest"));
        assertFalse(full.contains("mini.Hidden"));
    }

    @Test
    void rejectsAnEmptySuite() throws Exception {
        Path file = write("compiler/module.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <module xmlns="urn:mini:maven:module" name="compiler">
                  <tests><suite name="smoke"/></tests>
                </module>
                """);
        assertThrows(XmlException.class, () -> ModuleFile.load(file));
    }

    @Test
    void parsesAPlugin() throws Exception {
        Path file = write("plugins/javac/plugin.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <plugin xmlns="urn:mini:maven:plugin" name="javac" provides="java-compile">
                  <implementation class="mini.maven.plugins.JavacPlugin"/>
                  <tools><tool name="javac" path="${java.home}/bin/javac" versionArg="-version"/></tools>
                  <settings><setting name="release" value="17"/></settings>
                  <args><arg value="-g"/></args>
                  <cache scope="module-sources"/>
                </plugin>
                """);
        PluginFile plugin = PluginFile.load(file);

        assertEquals("javac", plugin.name());
        assertEquals("java-compile", plugin.provides());
        assertEquals("17", plugin.settings().get("release"));
        assertEquals("-g", plugin.args().get(0));
        assertEquals("module-sources", plugin.cacheScope());
        assertEquals("${java.home}/bin/javac", plugin.tool("javac").path());
    }

    @Test
    void missingStateIsEmpty() {
        StateFile state = StateFile.loadOrEmpty(tmp.resolve("state.xml"), "test");
        assertEquals(0, state.modules().size());
    }
}
