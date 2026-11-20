package mini.maven.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import mini.maven.xml.Xml;
import mini.maven.xml.XmlNode;

public record StateFile(
        Path file,
        int format,
        String engine,
        String generated,
        List<ModuleState> modules) {

    public static final String NS = "urn:mini:maven:state";

    public StateFile {
        modules = List.copyOf(modules);
    }

    public static StateFile empty(Path file, String engine) {
        return new StateFile(file, 1, engine, "", List.of());
    }

    public static StateFile loadOrEmpty(Path file, String engine) {
        if (!Files.isRegularFile(file)) return empty(file, engine);
        try {
            return load(file);
        } catch (RuntimeException e) {
            System.err.println("mini-mvn: ignoring unreadable state " + file + ": "
                    + e.getMessage());
            return empty(file, engine);
        }
    }

    public static StateFile load(Path file) {
        XmlNode root = Xml.root(file, "state", NS);
        root.refuseUnknownChildren("module");
        int format = parseInt(root.requireAttr("format"), "format");
        if (format != 1) throw root.error("unsupported state format " + format);
        String engine = root.requireAttr("engine");
        String generated = root.requireAttr("generated");

        List<ModuleState> modules = new ArrayList<>();
        for (XmlNode moduleNode : root.children("module")) {
            moduleNode.refuseUnknownChildren("node");
            List<NodeState> nodes = new ArrayList<>();
            for (XmlNode node : moduleNode.children("node")) {
                node.refuseUnknownChildren("input", "output");
                nodes.add(new NodeState(node.requireAttr("id"), node.requireAttr("key"),
                        node.attr("tool"), files(node, "input"), files(node, "output")));
            }
            modules.add(new ModuleState(moduleNode.requireAttr("name"), nodes));
        }
        return new StateFile(file, format, engine, generated, modules);
    }

    public ModuleState module(String name) {
        for (ModuleState module : modules) {
            if (module.name().equals(name)) return module;
        }
        return null;
    }

    private static List<FileState> files(XmlNode node, String child) {
        List<FileState> out = new ArrayList<>();
        for (XmlNode file : node.children(child)) {
            out.add(new FileState(file.requireAttr("path"), file.requireAttr("sha256"),
                    file.longAttr("size"), file.longAttr("mtime")));
        }
        return out;
    }

    private static int parseInt(String value, String what) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be an integer, got '" + value + "'");
        }
    }

    public record ModuleState(String name, List<NodeState> nodes) {

        public ModuleState {
            nodes = List.copyOf(nodes);
        }

        public NodeState node(String id) {
            for (NodeState node : nodes) {
                if (node.id().equals(id)) return node;
            }
            return null;
        }
    }

    public record NodeState(String id, String key, String tool, List<FileState> inputs,
                            List<FileState> outputs) {

        public NodeState {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    public record FileState(String path, String sha256, long size, long mtime) {
    }
}
