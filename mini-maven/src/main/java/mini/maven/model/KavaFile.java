package mini.maven.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mini.maven.xml.Xml;
import mini.maven.xml.XmlNode;

public record KavaFile(
        Path file,
        String name,
        String version,
        List<Platform> platforms,
        List<ModuleRef> modules,
        List<PluginRef> plugins,
        List<Binding> bindings,
        List<Goal> goals) {

    public static final String NS = "urn:mini:maven:kava";

    public KavaFile {
        platforms = List.copyOf(platforms);
        modules = List.copyOf(modules);
        plugins = List.copyOf(plugins);
        bindings = List.copyOf(bindings);
        goals = List.copyOf(goals);
    }

    public static KavaFile load(Path file) {
        XmlNode root = Xml.root(file, "project", NS);
        root.refuseUnknownChildren("name", "version", "platforms", "modules", "plugins",
                "bindings", "goals");
        String format = root.requireAttr("format");
        if (!"1".equals(format)) {
            throw root.error("unsupported project format '" + format + "'");
        }
        String name = requiredText(root, "name");
        String version = optionalText(root, "version");

        List<Platform> platforms = new ArrayList<>();
        Map<String, Platform> byId = new LinkedHashMap<>();
        XmlNode platformsNode = root.requireChild("platforms");
        for (XmlNode node : platformsNode.children("platform")) {
            Platform platform = new Platform(node.requireAttr("id"), node.requireAttr("arch"),
                    node.requireAttr("os"));
            if (byId.putIfAbsent(platform.id(), platform) != null) {
                throw node.error("duplicate platform '" + platform.id() + "'");
            }
            platforms.add(platform);
        }
        if (platforms.isEmpty()) throw platformsNode.error("<platforms> is empty");

        List<ModuleRef> modules = new ArrayList<>();
        Map<String, ModuleRef> byModule = new LinkedHashMap<>();
        XmlNode modulesNode = root.requireChild("modules");
        for (XmlNode node : modulesNode.children("module")) {
            ModuleRef module = new ModuleRef(node.requireAttr("name"),
                    Path.of(node.requireAttr("path")));
            if (byModule.putIfAbsent(module.name(), module) != null) {
                throw node.error("duplicate module '" + module.name() + "'");
            }
            modules.add(module);
        }
        if (modules.isEmpty()) throw modulesNode.error("<modules> is empty");

        List<PluginRef> plugins = new ArrayList<>();
        Map<String, PluginRef> byPlugin = new LinkedHashMap<>();
        XmlNode pluginsNode = root.requireChild("plugins");
        for (XmlNode node : pluginsNode.children("plugin")) {
            String pluginPath = optionalAttr(node, "path");
            PluginRef plugin = new PluginRef(node.requireAttr("id"),
                    pluginPath == null ? null : Path.of(pluginPath));
            if (byPlugin.putIfAbsent(plugin.id(), plugin) != null) {
                throw node.error("duplicate plugin '" + plugin.id() + "'");
            }
            plugins.add(plugin);
        }

        List<Binding> bindings = new ArrayList<>();
        XmlNode bindingsNode = root.child("bindings");
        if (bindingsNode != null) {
            for (XmlNode node : bindingsNode.children("binding")) {
                String module = optionalAttr(node, "module");
                String sources = optionalAttr(node, "sources");
                String artifact = optionalAttr(node, "artifact");
                String plugin = node.requireAttr("plugin");
                if (module == null && sources == null && artifact == null) {
                    throw node.error("a binding needs module, sources, or artifact");
                }
                if (!byModule.containsKey(module) && module != null) {
                    throw node.error("binding names unknown module '" + module + "'");
                }
                if (!byPlugin.containsKey(plugin)) {
                    throw node.error("binding names unknown plugin '" + plugin + "'");
                }
                bindings.add(new Binding(module, sources, artifact, plugin));
            }
        }

        List<Goal> goals = new ArrayList<>();
        Map<String, Goal> byGoal = new LinkedHashMap<>();
        XmlNode goalsNode = root.child("goals");
        if (goalsNode != null) {
            for (XmlNode node : goalsNode.children("goal")) {
                Goal goal = new Goal(node.requireAttr("name"), optionalAttr(node, "plugin"),
                        optionalAttr(node, "suite"), node.booleanAttr("default", false));
                if (byGoal.putIfAbsent(goal.name(), goal) != null) {
                    throw node.error("duplicate goal '" + goal.name() + "'");
                }
                if (goal.plugin() != null && !byPlugin.containsKey(goal.plugin())) {
                    throw node.error("goal names unknown plugin '" + goal.plugin() + "'");
                }
                goals.add(goal);
            }
        }

        return new KavaFile(file, name, version, platforms, modules, plugins, bindings, goals);
    }

    public ModuleRef module(String name) {
        for (ModuleRef module : modules) {
            if (module.name().equals(name)) return module;
        }
        return null;
    }

    public PluginRef plugin(String id) {
        for (PluginRef plugin : plugins) {
            if (plugin.id().equals(id)) return plugin;
        }
        return null;
    }

    public Goal goal(String name) {
        for (Goal goal : goals) {
            if (goal.name().equals(name)) return goal;
        }
        return null;
    }

    public Goal defaultGoal() {
        for (Goal goal : goals) {
            if (goal.isDefault()) return goal;
        }
        return null;
    }

    private static String requiredText(XmlNode parent, String childName) {
        XmlNode child = parent.requireChild(childName);
        String text = child.text();
        if (text.isEmpty()) throw child.error("<" + childName + "> is empty");
        return text;
    }

    private static String optionalText(XmlNode parent, String childName) {
        XmlNode child = parent.child(childName);
        if (child == null) return null;
        String text = child.text();
        return text.isEmpty() ? null : text;
    }

    private static String optionalAttr(XmlNode node, String name) {
        String value = node.attr(name);
        return value == null || value.isBlank() ? null : value;
    }

    public record Platform(String id, String arch, String os) {
    }

    public record ModuleRef(String name, Path path) {
    }

    public record PluginRef(String id, Path path) {
    }

    public record Binding(String module, String sources, String artifact, String plugin) {
    }

    public record Goal(String name, String plugin, String suite, boolean isDefault) {
    }
}
