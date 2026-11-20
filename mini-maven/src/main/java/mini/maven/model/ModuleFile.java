package mini.maven.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mini.maven.xml.Xml;
import mini.maven.xml.XmlNode;

public record ModuleFile(
        Path file,
        String name,
        List<SourceSet> sources,
        List<ResourceSet> resources,
        List<SourceSet> testSources,
        List<ResourceSet> testResources,
        List<IncludeDir> includes,
        List<Define> defines,
        List<Dependency> dependencies,
        List<Artifact> artifacts,
        List<Suite> suites) {

    public static final String NS = "urn:mini:maven:module";
    private static final Set<String> ARTIFACT_TYPES =
            Set.of("jar", "uberjar", "archive", "shared", "executable");

    public ModuleFile {
        sources = List.copyOf(sources);
        resources = List.copyOf(resources);
        testSources = List.copyOf(testSources);
        testResources = List.copyOf(testResources);
        includes = List.copyOf(includes);
        defines = List.copyOf(defines);
        dependencies = List.copyOf(dependencies);
        artifacts = List.copyOf(artifacts);
        suites = List.copyOf(suites);
    }

    public static ModuleFile load(Path file) {
        XmlNode root = Xml.root(file, "module", NS);
        root.refuseUnknownChildren("sources", "resources", "testSources", "testResources",
                "compile", "dependencies", "artifacts", "tests");
        String name = root.requireAttr("name");
        Path base = file.toAbsolutePath().getParent();

        List<SourceSet> sources = sourceSets(root.child("sources"), base);
        List<ResourceSet> resources = resourceSets(root.child("resources"), base);
        List<SourceSet> testSources = sourceSets(root.child("testSources"), base);
        List<ResourceSet> testResources = resourceSets(root.child("testResources"), base);

        List<IncludeDir> includes = new ArrayList<>();
        List<Define> defines = new ArrayList<>();
        XmlNode compile = root.child("compile");
        if (compile != null) {
            compile.refuseUnknownChildren("include", "define");
            for (XmlNode node : compile.children("include")) {
                includes.add(new IncludeDir(resolve(base, node.requireAttr("dir"))));
            }
            for (XmlNode node : compile.children("define")) {
                defines.add(new Define(node.requireAttr("name"),
                        node.attr("value") == null ? "1" : node.attr("value")));
            }
        }

        List<Dependency> dependencies = new ArrayList<>();
        XmlNode dependenciesNode = root.child("dependencies");
        if (dependenciesNode != null) {
            dependenciesNode.refuseUnknownChildren("dependency");
            for (XmlNode node : dependenciesNode.children("dependency")) {
                dependencies.add(dependency(node));
            }
        }

        List<Artifact> artifacts = new ArrayList<>();
        Map<String, Artifact> byId = new LinkedHashMap<>();
        XmlNode artifactsNode = root.child("artifacts");
        if (artifactsNode != null) {
            artifactsNode.refuseUnknownChildren("artifact");
            for (XmlNode node : artifactsNode.children("artifact")) {
                Artifact artifact = new Artifact(node.requireAttr("id"),
                        node.requireAttr("type"), node.attr("mainClass"),
                        node.booleanAttr("tests", false));
                if (!ARTIFACT_TYPES.contains(artifact.type())) {
                    throw node.error("unknown artifact type '" + artifact.type() + "'");
                }
                if (byId.putIfAbsent(artifact.id(), artifact) != null) {
                    throw node.error("duplicate artifact '" + artifact.id() + "'");
                }
                artifacts.add(artifact);
            }
        }

        List<Suite> suites = new ArrayList<>();
        Map<String, Suite> bySuite = new LinkedHashMap<>();
        XmlNode testsNode = root.child("tests");
        if (testsNode != null) {
            testsNode.refuseUnknownChildren("suite");
            for (XmlNode node : testsNode.children("suite")) {
                Suite suite = suite(node);
                if (bySuite.putIfAbsent(suite.name(), suite) != null) {
                    throw node.error("duplicate test suite '" + suite.name() + "'");
                }
                suites.add(suite);
            }
        }

        return new ModuleFile(file, name, sources, resources, testSources, testResources,
                includes, defines, dependencies, artifacts, suites);
    }

    public Suite suite(String name) {
        for (Suite suite : suites) {
            if (suite.name().equals(name)) return suite;
        }
        return null;
    }

    public Artifact artifact(String id) {
        for (Artifact artifact : artifacts) {
            if (artifact.id().equals(id)) return artifact;
        }
        return null;
    }

    public List<SourceSet> sources(String language) {
        List<SourceSet> out = new ArrayList<>();
        for (SourceSet source : sources) {
            if (source.language().equals(language)) out.add(source);
        }
        return out;
    }

    public List<SourceSet> testSources(String language) {
        List<SourceSet> out = new ArrayList<>();
        for (SourceSet source : testSources) {
            if (source.language().equals(language)) out.add(source);
        }
        return out;
    }

    private static List<SourceSet> sourceSets(XmlNode set, Path base) {
        List<SourceSet> out = new ArrayList<>();
        if (set == null) return out;
        set.refuseUnknownChildren("source");
        for (XmlNode node : set.children("source")) {
            out.add(new SourceSet(node.requireAttr("language"),
                    resolve(base, node.requireAttr("dir"))));
        }
        return out;
    }

    private static List<ResourceSet> resourceSets(XmlNode set, Path base) {
        List<ResourceSet> out = new ArrayList<>();
        if (set == null) return out;
        set.refuseUnknownChildren("resource");
        for (XmlNode node : set.children("resource")) {
            out.add(new ResourceSet(resolve(base, node.requireAttr("dir"))));
        }
        return out;
    }

    private static Dependency dependency(XmlNode node) {
        String module = optional(node, "module");
        String group = optional(node, "group");
        String artifact = optional(node, "artifact");
        String version = optional(node, "version");
        String scope = optional(node, "scope");
        if (scope == null) scope = "compile";
        if (module != null) {
            if (group != null || artifact != null || version != null) {
                throw node.error("a module dependency names only 'module'");
            }
        } else {
            if (group == null || artifact == null || version == null) {
                throw node.error("a jar dependency needs group, artifact, and version");
            }
        }
        return new Dependency(module, group, artifact, version, scope);
    }

    private static Suite suite(XmlNode node) {
        List<Selector> includes = new ArrayList<>();
        List<Selector> excludes = new ArrayList<>();
        node.refuseUnknownChildren("include", "exclude");
        for (XmlNode include : node.children("include")) {
            includes.add(selector(include));
        }
        for (XmlNode exclude : node.children("exclude")) {
            excludes.add(selector(exclude));
        }
        if (includes.isEmpty()) throw node.error("suite '" + node.attr("name") + "' is empty");
        return new Suite(node.requireAttr("name"), includes, excludes);
    }

    private static Selector selector(XmlNode node) {
        String className = optional(node, "class");
        String packageName = optional(node, "package");
        if ((className == null) == (packageName == null)) {
            throw node.error("an include/exclude needs exactly one of 'class' or 'package'");
        }
        return new Selector(className, packageName);
    }

    private static String optional(XmlNode node, String name) {
        String value = node.attr(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static Path resolve(Path base, String path) {
        Path p = Path.of(path);
        return (p.isAbsolute() ? p : base.resolve(p)).normalize();
    }

    public record SourceSet(String language, Path dir) {
    }

    public record ResourceSet(Path dir) {
    }

    public record IncludeDir(Path dir) {
    }

    public record Define(String name, String value) {
    }

    public record Dependency(String module, String group, String artifact, String version,
                             String scope) {

        public boolean isModule() {
            return module != null;
        }

        public String coordinates() {
            return group + ":" + artifact + ":" + version;
        }
    }

    public record Artifact(String id, String type, String mainClass, boolean tests) {
    }

    public record Selector(String className, String packageName) {

        public boolean matches(String candidate) {
            if (className != null) return className.equals(candidate);
            return candidate.startsWith(packageName + ".");
        }
    }

    public record Suite(String name, List<Selector> includes, List<Selector> excludes) {

        public Suite {
            includes = List.copyOf(includes);
            excludes = List.copyOf(excludes);
        }

        public boolean contains(String className) {
            boolean included = false;
            for (Selector selector : includes) {
                if (selector.matches(className)) {
                    included = true;
                    break;
                }
            }
            if (!included) return false;
            for (Selector selector : excludes) {
                if (selector.matches(className)) return false;
            }
            return true;
        }
    }
}
