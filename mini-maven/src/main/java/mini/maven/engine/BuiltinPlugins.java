package mini.maven.engine;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import mini.maven.model.PluginFile;

public final class BuiltinPlugins {

    private static final String JAVAC = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugin xmlns="urn:mini:maven:plugin" name="javac" provides="java-compile">
              <implementation class="mini.maven.plugins.JavacPlugin"/>
              <tools>
                <tool name="javac" path="${java.home}/bin/javac" versionArg="-version"/>
              </tools>
              <settings>
                <setting name="release" value="17"/>
                <setting name="encoding" value="UTF-8"/>
              </settings>
              <args>
                <arg value="-g"/>
              </args>
              <cache scope="module-sources"/>
            </plugin>
            """;

    private static final String JAR = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugin xmlns="urn:mini:maven:plugin" name="jar" provides="jar">
              <implementation class="mini.maven.plugins.JarPlugin"/>
              <tools>
                <tool name="jar" path="${java.home}/bin/jar" versionArg="--version"/>
              </tools>
              <settings>
                <setting name="version" value="2.0.0"/>
              </settings>
              <cache scope="module-artifact"/>
            </plugin>
            """;

    private static final String JUNIT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugin xmlns="urn:mini:maven:plugin" name="junit" provides="test-runner">
              <implementation class="mini.maven.plugins.JunitPlugin"/>
              <tools>
                <tool name="java" path="${java.home}/bin/java" versionArg="-version"/>
              </tools>
              <settings>
                <setting name="launcher" value="org.junit.platform.launcher.core.LauncherFactory"/>
                <setting name="reports" value="target/test-reports"/>
              </settings>
              <cache scope="test-classes"/>
            </plugin>
            """;

    private static final String KAVAC = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugin xmlns="urn:mini:maven:plugin" name="kavac" provides="java-compile">
              <implementation class="mini.maven.plugins.KavacPlugin"/>
              <tools>
                <tool name="java" path="${java.home}/bin/java" versionArg="-version"/>
              </tools>
              <settings>
                <setting name="mainClass" value="mini.MiniCompiler"/>
                <setting name="emit" value="lib"/>
              </settings>
              <cache scope="module-sources"/>
            </plugin>
            """;

    private static final String GCC = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugin xmlns="urn:mini:maven:plugin" name="gcc" provides="c-compile,shared-link">
              <implementation class="mini.maven.plugins.GccPlugin"/>
              <tools>
                <tool name="cc" path="${platform}-gcc" versionArg="--version"/>
              </tools>
              <settings>
                <setting name="pic" value="true"/>
                <setting name="debug" value="true"/>
                <setting name="optimize" value="2"/>
                <setting name="deps" value="true"/>
              </settings>
              <args>
                <arg value="-fno-omit-frame-pointer"/>
                <arg value="-pthread"/>
                <arg value="-Wall"/>
                <arg value="-Wextra"/>
              </args>
              <cache scope="tool-and-flags"/>
            </plugin>
            """;

    private static final String AR = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugin xmlns="urn:mini:maven:plugin" name="ar" provides="archive">
              <implementation class="mini.maven.plugins.ArPlugin"/>
              <tools>
                <tool name="ar" path="ar" versionArg="--version"/>
              </tools>
              <cache scope="tool-and-flags"/>
            </plugin>
            """;

    private static final Map<String, String> DEFINITIONS = definitions();

    private BuiltinPlugins() {
    }

    public static boolean has(String id) {
        return DEFINITIONS.containsKey(id);
    }

    public static PluginFile get(String id) {
        String xml = DEFINITIONS.get(id);
        if (xml == null) throw new BuildException("unknown built-in plugin: " + id);
        return PluginFile.parse(xml, Path.of("<builtin:" + id + ">"));
    }

    public static Map<String, PluginFile> all() {
        Map<String, PluginFile> plugins = new LinkedHashMap<>();
        for (String id : DEFINITIONS.keySet()) plugins.put(id, get(id));
        return plugins;
    }

    private static Map<String, String> definitions() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("javac", JAVAC);
        out.put("jar", JAR);
        out.put("junit", JUNIT);
        out.put("kavac", KAVAC);
        out.put("gcc", GCC);
        out.put("ar", AR);
        return out;
    }
}
