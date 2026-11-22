package mini.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

import mini.maven.engine.BuildException;
import mini.maven.engine.BuiltinPlugins;

class BuiltinPluginsTest {

    @Test
    void exposesAllBuiltinDefinitions() {
        var plugins = BuiltinPlugins.all();

        assertEquals(Set.of("javac", "jar", "junit", "kavac", "gcc", "ar"), plugins.keySet());
        assertEquals("java-compile", plugins.get("javac").provides());
        assertEquals("jar", plugins.get("jar").name());
        assertEquals("test-runner", plugins.get("junit").provides());
        assertNotNull(plugins.get("kavac").settings().get("mainClass"));
        assertNotNull(plugins.get("gcc").tool("cc"));
        assertNotNull(plugins.get("ar").tool("ar"));
    }

    @Test
    void rejectsUnknownPlugin() {
        assertThrows(BuildException.class, () -> BuiltinPlugins.get("missing"));
    }
}
