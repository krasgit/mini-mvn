package mini.maven.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import mini.maven.model.StateFile;
import mini.maven.model.StateFile.FileState;
import mini.maven.model.StateFile.ModuleState;
import mini.maven.model.StateFile.NodeState;

public final class StateStore implements AutoCloseable {

    private final Path root;
    private final Path file;
    private final String engine;
    private final Map<String, Map<String, NodeState>> modules = new TreeMap<>();
    private final Map<String, FileState> known = new TreeMap<>();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean dirty;

    public StateStore(Path root, String engine) {
        this.root = root.toAbsolutePath().normalize();
        this.file = this.root.resolve("target").resolve(".mini-maven").resolve("state.xml");
        this.engine = engine;
        try {
            Files.createDirectories(file.getParent());
            this.lockChannel = FileChannel.open(file.resolveSibling("lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired;
            try {
                acquired = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                acquired = null;
            }
            if (acquired == null) {
                lockChannel.close();
                throw new BuildException("another mini-mvn is already building in " + root);
            }
            this.lock = acquired;
        } catch (IOException e) {
            throw new BuildException("cannot lock " + file, e);
        }
        load();
    }

    private void load() {
        StateFile state = StateFile.loadOrEmpty(file, engine);
        for (ModuleState module : state.modules()) {
            Map<String, NodeState> nodes = new TreeMap<>();
            for (NodeState node : module.nodes()) {
                nodes.put(node.id(), node);
                for (FileState input : node.inputs()) known.put(input.path(), input);
                for (FileState output : node.outputs()) known.put(output.path(), output);
            }
            modules.put(module.name(), nodes);
        }
    }

    public NodeState node(String module, String id) {
        Map<String, NodeState> nodes = modules.get(module);
        return nodes == null ? null : nodes.get(id);
    }

    public boolean fresh(String module, String id, String key, List<Path> outputs) {
        NodeState node = node(module, id);
        if (node == null || !node.key().equals(key)) return false;
        if (node.outputs().size() != outputs.size()) return false;
        Map<String, FileState> recorded = new TreeMap<>();
        for (FileState output : node.outputs()) recorded.put(output.path(), output);
        for (Path output : outputs) {
            if (!Files.isRegularFile(output)) return false;
            FileState state = recorded.get(pathKey(output));
            if (state == null) return false;
            try {
                if (Files.size(output) != state.size()) return false;
                if (Files.getLastModifiedTime(output).toMillis() != state.mtime()) return false;
                // Size and mtime can be preserved by a content edit: verify the recorded hash.
                try {
                    if (state.sha256() == null
                            || !state.sha256().equals(Hashing.sha256(output))) return false;
                } catch (IOException e) {
                    return false;   // unreadable output is not fresh
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    public void record(String module, String id, String key, String tool, List<Path> inputs,
                       List<Path> outputs) {
        List<FileState> in = new ArrayList<>();
        for (Path input : sorted(expand(inputs))) in.add(fileState(input));
        List<FileState> out = new ArrayList<>();
        for (Path output : sorted(outputs)) out.add(fileState(output));
        modules.computeIfAbsent(module, ignored -> new TreeMap<>())
                .put(id, new NodeState(id, key, tool, in, out));
        dirty = true;
    }

    public FileState fileState(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String key = pathKey(absolute);
        try {
            long size = Files.size(absolute);
            long mtime = Files.getLastModifiedTime(absolute).toMillis();
            FileState previous = known.get(key);
            if (previous != null && previous.size() == size && previous.mtime() == mtime) {
                return previous;
            }
            FileState state = new FileState(key, Hashing.sha256(absolute), size, mtime);
            known.put(key, state);
            return state;
        } catch (IOException e) {
            throw new BuildException("cannot hash " + absolute, e);
        }
    }

    public String pathKey(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String key;
        try {
            key = root.relativize(absolute).toString();
        } catch (IllegalArgumentException e) {
            key = absolute.toString();
        }
        return key.replace('\\', '/');
    }

    public void save() {
        if (!dirty) return;
        String xml = render();
        Path tmp = file.resolveSibling("state.xml.tmp");
        try {
            Files.writeString(tmp, xml, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            throw new BuildException("cannot write " + file, e);
        }
    }

    private String render() {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<state xmlns=\"urn:mini:maven:state\" format=\"1\" engine=\"")
                .append(escape(engine)).append("\" generated=\"")
                .append(Instant.now().toString()).append("\">\n");
        for (Map.Entry<String, Map<String, NodeState>> module : modules.entrySet()) {
            out.append("  <module name=\"").append(escape(module.getKey())).append("\">\n");
            for (NodeState node : module.getValue().values()) {
                out.append("    <node id=\"").append(escape(node.id())).append("\" key=\"")
                        .append(escape(node.key())).append("\"");
                if (node.tool() != null) {
                    out.append(" tool=\"").append(escape(node.tool())).append("\"");
                }
                out.append(">\n");
                for (FileState input : node.inputs()) renderFile(out, "input", input);
                for (FileState output : node.outputs()) renderFile(out, "output", output);
                out.append("    </node>\n");
            }
            out.append("  </module>\n");
        }
        out.append("</state>\n");
        return out.toString();
    }

    private static void renderFile(StringBuilder out, String element, FileState state) {
        out.append("      <").append(element)
                .append(" path=\"").append(escape(state.path()))
                .append("\" sha256=\"").append(escape(state.sha256()))
                .append("\" size=\"").append(state.size())
                .append("\" mtime=\"").append(state.mtime())
                .append("\"/>\n");
    }

    private static List<Path> expand(List<Path> paths) {
        List<Path> out = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile).forEach(out::add);
                } catch (IOException e) {
                    throw new BuildException("cannot list " + path, e);
                }
            } else {
                out.add(path);
            }
        }
        return out;
    }

    private static List<Path> sorted(List<Path> paths) {
        List<Path> copy = new ArrayList<>(paths);
        copy.sort(Comparator.comparing(Path::toString));
        return copy;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public Path stateFile() {
        return file;
    }

    @Override
    public void close() {
        try {
            save();
        } finally {
            try {
                lock.release();
            } catch (IOException ignored) {
                // closing the channel below releases it anyway
            }
            try {
                lockChannel.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }
}
