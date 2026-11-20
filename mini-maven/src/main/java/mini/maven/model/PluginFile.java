package mini.maven.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mini.maven.xml.Xml;
import mini.maven.xml.XmlNode;

public record PluginFile(
        Path file,
        String name,
        String provides,
        String implementation,
        List<Tool> tools,
        Map<String, String> settings,
        List<String> args,
        String cacheScope) {

    public static final String NS = "urn:mini:maven:plugin";

    public PluginFile {
        tools = List.copyOf(tools);
        settings = Map.copyOf(settings);
        args = List.copyOf(args);
    }

    public static PluginFile load(Path file) {
        XmlNode root = Xml.root(file, "plugin", NS);
        root.refuseUnknownChildren("implementation", "tools", "settings", "args", "cache");
        String name = root.requireAttr("name");
        String provides = root.requireAttr("provides");

        XmlNode implementation = root.requireChild("implementation");
        String implementationClass = implementation.requireAttr("class");

        List<Tool> tools = new ArrayList<>();
        Map<String, Tool> byName = new LinkedHashMap<>();
        XmlNode toolsNode = root.child("tools");
        if (toolsNode != null) {
            toolsNode.refuseUnknownChildren("tool");
            for (XmlNode node : toolsNode.children("tool")) {
                Tool tool = new Tool(node.requireAttr("name"), node.requireAttr("path"),
                        node.attr("versionArg"));
                if (byName.putIfAbsent(tool.name(), tool) != null) {
                    throw node.error("duplicate tool '" + tool.name() + "'");
                }
                tools.add(tool);
            }
        }

        Map<String, String> settings = new LinkedHashMap<>();
        XmlNode settingsNode = root.child("settings");
        if (settingsNode != null) {
            settingsNode.refuseUnknownChildren("setting");
            for (XmlNode node : settingsNode.children("setting")) {
                String key = node.requireAttr("name");
                if (settings.putIfAbsent(key, node.requireAttr("value")) != null) {
                    throw node.error("duplicate setting '" + key + "'");
                }
            }
        }

        List<String> args = new ArrayList<>();
        XmlNode argsNode = root.child("args");
        if (argsNode != null) {
            argsNode.refuseUnknownChildren("arg");
            for (XmlNode node : argsNode.children("arg")) {
                args.add(node.requireAttr("value"));
            }
        }

        String cacheScope = null;
        XmlNode cache = root.child("cache");
        if (cache != null) {
            cacheScope = cache.requireAttr("scope");
        }

        return new PluginFile(file, name, provides, implementationClass, tools, settings,
                args, cacheScope);
    }

    public Tool tool(String name) {
        for (Tool tool : tools) {
            if (tool.name().equals(name)) return tool;
        }
        return null;
    }

    public record Tool(String name, String path, String versionArg) {
    }
}
