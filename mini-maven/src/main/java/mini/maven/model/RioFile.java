package mini.maven.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mini.maven.xml.Xml;
import mini.maven.xml.XmlNode;

public record RioFile(
        Path file,
        String name,
        String repository,
        String ref,
        String defaultRuntime,
        String defaultPlatform,
        Run run,
        List<Platform> platforms,
        List<Profile> profiles,
        List<Dependency> dependencies,
        List<Artifact> artifacts) {

    public static final String NS = "urn:mini:rio:project";

    public RioFile {
        platforms = List.copyOf(platforms);
        profiles = List.copyOf(profiles);
        dependencies = List.copyOf(dependencies);
        artifacts = List.copyOf(artifacts);
    }

    public static RioFile load(Path file) {
        XmlNode root = Xml.root(file, "rio", NS);
        root.refuseUnknownChildren("name", "repository", "ref", "defaultRuntime",
                "defaultPlatform", "run", "platforms", "profiles", "dependencies", "artifacts");
        String format = root.requireAttr("format");
        if (!"1".equals(format)) {
            throw root.error("unsupported rio format '" + format + "'");
        }

        String name = requiredText(root, "name");
        String repository = requiredText(root, "repository");
        String ref = optionalText(root, "ref");
        if (ref == null) ref = "main";
        String defaultRuntime = optionalText(root, "defaultRuntime");
        if (defaultRuntime == null) defaultRuntime = "java";
        String defaultPlatform = optionalText(root, "defaultPlatform");
        if (defaultPlatform == null) defaultPlatform = "default";
        checkRuntime(root, defaultRuntime, "defaultRuntime");

        XmlNode runNode = root.requireChild("run");
        Run run = new Run(runNode.requireAttr("main"), runNode.requireAttr("browser"),
                runNode.requireAttr("port"), optionalAttr(runNode, "workspace", "."));

        List<Platform> platforms = new ArrayList<>();
        Map<String, Platform> byPlatform = new LinkedHashMap<>();
        XmlNode platformsNode = root.requireChild("platforms");
        for (XmlNode node : platformsNode.children("platform")) {
            Platform platform = new Platform(node.requireAttr("name"),
                    node.requireAttr("runtime"), optionalAttr(node, "target", null),
                    optionalAttr(node, "cc", null));
            checkRuntime(node, platform.runtime(), "runtime");
            if (byPlatform.putIfAbsent(platform.name(), platform) != null) {
                throw node.error("duplicate platform '" + platform.name() + "'");
            }
            platforms.add(platform);
        }
        if (platforms.isEmpty()) throw platformsNode.error("<platforms> is empty");

        List<Profile> profiles = new ArrayList<>();
        Map<String, Profile> byProfile = new LinkedHashMap<>();
        XmlNode profilesNode = root.requireChild("profiles");
        for (XmlNode node : profilesNode.children("profile")) {
            Profile profile = new Profile(node.requireAttr("name"),
                    optionalAttr(node, "runtime", null), optionalAttr(node, "target", null),
                    optionalAttr(node, "cc", null), node.booleanAttr("static", false),
                    node.booleanAttr("noOpt", false), optionalAttr(node, "gc", null));
            if (profile.runtime() != null) checkRuntime(node, profile.runtime(), "runtime");
            if (byProfile.putIfAbsent(profile.name(), profile) != null) {
                throw node.error("duplicate profile '" + profile.name() + "'");
            }
            profiles.add(profile);
        }

        List<Dependency> dependencies = new ArrayList<>();
        Map<String, Dependency> byDependency = new LinkedHashMap<>();
        XmlNode dependenciesNode = root.child("dependencies");
        if (dependenciesNode != null) {
            for (XmlNode node : dependenciesNode.children("dependency")) {
                Dependency dependency = new Dependency(node.requireAttr("name"),
                        node.requireAttr("repository"), optionalAttr(node, "ref", "main"),
                        optionalAttr(node, "kind", "source"), optionalAttr(node, "install", null));
                if (byDependency.putIfAbsent(dependency.name(), dependency) != null) {
                    throw node.error("duplicate dependency '" + dependency.name() + "'");
                }
                dependencies.add(dependency);
            }
        }

        List<Artifact> artifacts = new ArrayList<>();
        Map<String, Artifact> byArtifact = new LinkedHashMap<>();
        XmlNode artifactsNode = root.child("artifacts");
        if (artifactsNode != null) {
            for (XmlNode node : artifactsNode.children("artifact")) {
                Artifact artifact = new Artifact(node.requireAttr("id"),
                        Path.of(node.requireAttr("path")), optionalAttr(node, "sha256", null));
                if (byArtifact.putIfAbsent(artifact.id(), artifact) != null) {
                    throw node.error("duplicate artifact '" + artifact.id() + "'");
                }
                artifacts.add(artifact);
            }
        }

        return new RioFile(file, name, repository, ref, defaultRuntime, defaultPlatform, run,
                platforms, profiles, dependencies, artifacts);
    }

    public Platform platform(String name) {
        for (Platform platform : platforms) {
            if (platform.name().equals(name)) return platform;
        }
        return null;
    }

    public Profile profile(String name) {
        for (Profile profile : profiles) {
            if (profile.name().equals(name)) return profile;
        }
        return null;
    }

    public Dependency dependency(String name) {
        for (Dependency dependency : dependencies) {
            if (dependency.name().equals(name)) return dependency;
        }
        return null;
    }

    public Resolution resolve(String platformName, String profileName) {
        String effectivePlatform = platformName == null ? defaultPlatform : platformName;
        Platform platform = platform(effectivePlatform);
        if (platform == null) {
            throw new IllegalArgumentException("unknown platform '" + effectivePlatform
                    + "'; expected one of: " + names(platforms.stream().map(Platform::name).toList()));
        }
        Profile profile = null;
        if (profileName != null) {
            profile = profile(profileName);
            if (profile == null) {
                throw new IllegalArgumentException("unknown profile '" + profileName
                        + "'; expected one of: " + names(profiles.stream().map(Profile::name).toList()));
            }
        }

        String runtime = platform.runtime();
        if (profile != null && profile.runtime() != null
                && !profile.runtime().equals(runtime)) {
            throw new IllegalArgumentException("profile '" + profile.name() + "' requires runtime "
                    + profile.runtime() + " but platform '" + platform.name() + "' is "
                    + runtime);
        }
        if (runtime.equals("java") && profile != null
                && (profile.name().equals("static") || profile.name().equals("dynamic"))) {
            throw new IllegalArgumentException("profile '" + profile.name()
                    + "' requires a native platform");
        }

        String target = profile != null && profile.target() != null
                ? profile.target() : platform.target();
        String cc = profile != null && profile.cc() != null ? profile.cc() : platform.cc();
        boolean linkStatic = profile != null && profile.staticLink();
        boolean noOpt = profile != null && profile.noOpt();
        String gc = profile != null && profile.gc() != null ? profile.gc() : "marksweep";
        if (runtime.equals("native") && target == null) {
            throw new IllegalArgumentException("platform '" + platform.name()
                    + "' has no native target");
        }
        return new Resolution(runtime, target, cc, linkStatic, noOpt, gc);
    }

    public static String names(List<String> values) {
        return String.join(", ", values);
    }

    private static void checkRuntime(XmlNode node, String runtime, String attribute) {
        if (!runtime.equals("java") && !runtime.equals("native")) {
            throw node.error("invalid " + attribute + " '" + runtime
                    + "'; expected java or native");
        }
    }

    private static String requiredText(XmlNode parent, String childName) {
        XmlNode child = parent.requireChild(childName);
        String text = child.text();
        if (text.isEmpty()) throw child.error("<" + childName + "> is empty");
        return text;
    }

    private static String optionalText(XmlNode parent, String childName) {
        XmlNode child = parent.child(childName);
        return child == null || child.text().isEmpty() ? null : child.text();
    }

    private static String optionalAttr(XmlNode node, String name, String fallback) {
        String value = node.attr(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Run(String main, String browser, String port, String workspace) {
    }

    public record Platform(String name, String runtime, String target, String cc) {
    }

    public record Profile(String name, String runtime, String target, String cc,
                          boolean staticLink, boolean noOpt, String gc) {
    }

    public record Dependency(String name, String repository, String ref, String kind,
                             String install) {
    }

    public record Artifact(String id, Path path, String sha256) {
    }

    public record Resolution(String runtime, String target, String cc, boolean staticLink,
                             boolean noOpt, String gc) {
    }
}
