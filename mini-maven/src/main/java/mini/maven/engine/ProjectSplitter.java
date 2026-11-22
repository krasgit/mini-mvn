package mini.maven.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mini.maven.model.KavaFile;
import mini.maven.model.ModuleFile;
import mini.maven.model.PluginFile;

public final class ProjectSplitter {

    public record Step(List<String> args, Path dir) {
    }

    public record Result(Path sourceRoot, Path sourceDescriptor, Path sourceModuleDir,
                         Path targetDir, List<Step> steps) {
    }

    private record Loaded(Map<String, ModuleFile> modules, Map<String, PluginFile> plugins) {
    }

    private ProjectSplitter() {
    }

    public static Result split(Path descriptor, String module, String repo, String visibility,
                               Path to, String group, boolean force, boolean dryRun,
                               boolean localOnly, boolean verbose) throws IOException {
        Result result = prepare(descriptor, module, repo, visibility, to, group, force,
                dryRun, verbose);
        if (!dryRun && !localOnly) {
            publish(result, repo, visibility, module, verbose);
        }
        return result;
    }

    public static Result prepare(Path descriptorArg, String module, String repo, String visibility,
                                 Path to, String group, boolean force, boolean dryRun,
                                 boolean verbose) throws IOException {
        Path descriptor = descriptorArg.toAbsolutePath().normalize();
        Path sourceRoot = descriptor.getParent();
        KavaFile project = KavaFile.load(descriptor);
        Loaded loaded = load(project, sourceRoot);
        KavaFile.ModuleRef ref = project.module(module);
        if (ref == null) {
            throw new BuildException("unknown module: " + module);
        }
        if (project.modules().size() < 2) {
            throw new BuildException("cannot split the only module of " + project.name());
        }
        if (repo == null || !repo.matches("[^/]+/[^/]+")) {
            throw new BuildException("repo must be owner/name, got '" + repo + "'");
        }
        if (!visibility.equals("public") && !visibility.equals("private")) {
            throw new BuildException("visibility must be public or private, got '" + visibility + "'");
        }

        String repoName = repo.substring(repo.indexOf('/') + 1);
        String owner = repo.substring(0, repo.indexOf('/'));
        String vendorGroup = group == null ? owner : group;
        String version = project.version() == null ? "1.0.0" : project.version();
        Path targetDir = to == null ? sourceRoot.getParent().resolve(repoName)
                : to.toAbsolutePath().normalize();
        if (Files.exists(targetDir) && !force) {
            throw new BuildException("target already exists: " + targetDir + " (use --force)");
        }

        LinkedHashSet<String> closure = new LinkedHashSet<>();
        collect(module, loaded.modules(), closure, new LinkedHashSet<>());

        if (!dryRun) {
            for (String name : closure) {
                if (name.equals(module)) continue;
                Path jar = moduleJar(sourceRoot, loaded.modules(), name);
                if (jar == null || !Files.isRegularFile(jar)) {
                    new Engine(sourceRoot, project, loaded.modules(), loaded.plugins(),
                            name, true, null, verbose, 1).build();
                }
            }
            Files.createDirectories(targetDir);
            copyTree(sourceRoot.resolve(ref.path()).normalize(),
                    targetDir.resolve(ref.path().getFileName().toString()), true);
        }

        for (String name : closure) {
            if (name.equals(module)) continue;
            ModuleFile dependencyModule = loaded.modules().get(name);
            Path jar = moduleJar(sourceRoot, loaded.modules(), name);
            if (dependencyModule == null || jar == null || !Files.isRegularFile(jar)) continue;
            String artifact = firstJarArtifact(dependencyModule);
            if (artifact == null) continue;
            Path destination = vendored(targetDir, vendorGroup, artifact, version);
            if (!dryRun) {
                copyFile(jar, destination);
                writeSha(destination);
            }
        }

        Set<ModuleFile.Dependency> external = new LinkedHashSet<>();
        for (String name : closure) {
            ModuleFile current = loaded.modules().get(name);
            if (current == null) continue;
            for (ModuleFile.Dependency dependency : current.dependencies()) {
                if (dependency.isModule()) continue;
                if (!dependency.scope().equals("compile") && !dependency.scope().equals("runtime")) {
                    continue;
                }
                external.add(dependency);
            }
        }
        for (ModuleFile.Dependency dependency : external) {
            Path source = vendored(sourceRoot, dependency);
            if (!Files.isRegularFile(source)) {
                throw new BuildException("missing vendored jar " + dependency.coordinates()
                        + " at " + sourceRoot.relativize(source));
            }
            Path destination = vendored(targetDir, dependency);
            if (!dryRun) {
                copyFile(source, destination);
                Path sourceSha = Path.of(source + ".sha256");
                Path destinationSha = Path.of(destination + ".sha256");
                if (Files.isRegularFile(sourceSha)) copyFile(sourceSha, destinationSha);
                else writeSha(destination);
            }
        }

        List<KavaFile.Binding> targetBindings = new ArrayList<>();
        for (KavaFile.Binding binding : project.bindings()) {
            if (binding.module() == null || binding.module().equals(module)) targetBindings.add(binding);
        }
        String moduleDir = ref.path().getFileName().toString();
        List<KavaFile.ModuleRef> targetModules = List.of(new KavaFile.ModuleRef(module, Path.of(moduleDir)));
        List<KavaFile.PluginRef> targetPlugins = builtinRefs(
                usedPlugins(project, module, targetBindings));
        String targetKava = kava(repoName, version, project.platforms(), targetModules,
                targetPlugins, targetBindings, project.goals());

        if (!dryRun) {
            for (KavaFile.PluginRef plugin : targetPlugins) {
                if (plugin.path() != null) {
                    copyTree(sourceRoot.resolve(plugin.path()).normalize(),
                            targetDir.resolve(plugin.path()), true);
                }
            }
            Files.writeString(targetDir.resolve("kava.xml"), targetKava, StandardCharsets.UTF_8);
            rewriteModuleDependency(targetDir.resolve(moduleDir).resolve("module.xml"),
                    loaded.modules(), closure, module, vendorGroup, version);
        }

        List<KavaFile.ModuleRef> remainingModules = new ArrayList<>();
        for (KavaFile.ModuleRef candidate : project.modules()) {
            if (!candidate.name().equals(module)) remainingModules.add(candidate);
        }
        List<KavaFile.Binding> remainingBindings = new ArrayList<>();
        for (KavaFile.Binding binding : project.bindings()) {
            if (!module.equals(binding.module())) remainingBindings.add(binding);
        }
        String sourceKava = kava(project.name(), project.version(), project.platforms(),
                remainingModules, builtinRefs(project.plugins()), remainingBindings, project.goals());
        if (!dryRun) {
            Files.writeString(descriptor, sourceKava, StandardCharsets.UTF_8);
            deleteTree(sourceRoot.resolve(ref.path()).normalize());
        }

        List<Step> steps = plannedSteps(sourceRoot, targetDir, descriptor, moduleDir,
                module, repo, visibility);
        return new Result(sourceRoot, descriptor, sourceRoot.resolve(ref.path()).normalize(),
                targetDir, steps);
    }

    private static List<KavaFile.PluginRef> usedPlugins(KavaFile project, String module,
                                                        List<KavaFile.Binding> bindings) {
        Set<String> ids = new LinkedHashSet<>();
        for (KavaFile.Binding binding : bindings) {
            if (binding.module() == null || binding.module().equals(module)) {
                ids.add(binding.plugin());
            }
        }
        for (KavaFile.Goal goal : project.goals()) {
            if (goal.plugin() != null) ids.add(goal.plugin());
        }
        List<KavaFile.PluginRef> out = new ArrayList<>();
        for (KavaFile.PluginRef ref : project.plugins()) {
            if (ids.contains(ref.id())) out.add(ref);
        }
        return out;
    }

    private static List<KavaFile.PluginRef> builtinRefs(List<KavaFile.PluginRef> refs) {
        List<KavaFile.PluginRef> out = new ArrayList<>();
        for (KavaFile.PluginRef ref : refs) {
            out.add(BuiltinPlugins.has(ref.id())
                    ? new KavaFile.PluginRef(ref.id(), null)
                    : ref);
        }
        return out;
    }

    private static Loaded load(KavaFile project, Path root) {
        Map<String, ModuleFile> modules = new LinkedHashMap<>();
        for (KavaFile.ModuleRef ref : project.modules()) {
            Path file = root.resolve(ref.path()).resolve("module.xml").normalize();
            ModuleFile module = ModuleFile.load(file);
            if (!module.name().equals(ref.name())) {
                throw new BuildException("module name '" + module.name()
                        + "' does not match kava.xml entry '" + ref.name() + "'");
            }
            modules.put(module.name(), module);
        }
        Map<String, PluginFile> plugins = new LinkedHashMap<>();
        for (KavaFile.PluginRef ref : project.plugins()) {
            PluginFile plugin;
            if (ref.path() == null) {
                plugin = BuiltinPlugins.get(ref.id());
            } else {
                Path file = root.resolve(ref.path()).resolve("plugin.xml").normalize();
                plugin = PluginFile.load(file);
            }
            if (!plugin.name().equals(ref.id())) {
                throw new BuildException("plugin name '" + plugin.name()
                        + "' does not match kava.xml entry '" + ref.id() + "'");
            }
            plugins.put(plugin.name(), plugin);
        }
        return new Loaded(modules, plugins);
    }

    private static void collect(String name, Map<String, ModuleFile> modules,
                                Set<String> closure, Set<String> visiting) {
        if (!closure.add(name)) return;
        if (!visiting.add(name)) return;
        ModuleFile module = modules.get(name);
        if (module != null) {
            for (ModuleFile.Dependency dependency : module.dependencies()) {
                if (!dependency.isModule()) continue;
                if (dependency.scope().equals("test")) continue;
                if (!modules.containsKey(dependency.module())) continue;
                collect(dependency.module(), modules, closure, visiting);
            }
        }
        visiting.remove(name);
    }

    private static Path moduleJar(Path root, Map<String, ModuleFile> modules, String name) {
        ModuleFile module = modules.get(name);
        if (module == null) return null;
        Path target = root.resolve("target").resolve(name);
        for (ModuleFile.Artifact artifact : module.artifacts()) {
            if (artifact.type().equals("jar") && !artifact.tests()) {
                return target.resolve(artifact.id() + ".jar");
            }
        }
        return null;
    }

    private static String firstJarArtifact(ModuleFile module) {
        for (ModuleFile.Artifact artifact : module.artifacts()) {
            if (artifact.type().equals("jar") && !artifact.tests()) return artifact.id();
        }
        return null;
    }

    private static Path vendored(Path root, String group, String artifact, String version) {
        return root.resolve("third-party").resolve("repository")
                .resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + ".jar");
    }

    private static Path vendored(Path root, ModuleFile.Dependency dependency) {
        return vendored(root, dependency.group(), dependency.artifact(), dependency.version());
    }

    private static void rewriteModuleDependency(Path file, Map<String, ModuleFile> modules,
                                                Set<String> closure, String splitModule,
                                                String group, String version) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        for (String name : closure) {
            if (name.equals(splitModule)) continue;
            ModuleFile module = modules.get(name);
            String artifact = module == null ? null : firstJarArtifact(module);
            if (artifact == null) continue;
            String pattern = "<dependency\\s+[^>]*module=\"" + Pattern.quote(name) + "\"[^>]*/>";
            String replacement = "<dependency group=\"" + group + "\" artifact=\""
                    + artifact + "\" version=\"" + version + "\"/>";
            text = text.replaceAll(pattern, Matcher.quoteReplacement(replacement));
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String kava(String name, String version, List<KavaFile.Platform> platforms,
                               List<KavaFile.ModuleRef> modules, List<KavaFile.PluginRef> plugins,
                               List<KavaFile.Binding> bindings, List<KavaFile.Goal> goals) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<project xmlns=\"urn:mini:maven:kava\" format=\"1\">\n");
        out.append("  <name>").append(escape(name)).append("</name>\n");
        out.append("  <version>").append(escape(version == null ? "1.0.0" : version))
                .append("</version>\n");
        out.append("  <platforms>\n");
        for (KavaFile.Platform platform : platforms) {
            out.append("    <platform id=\"").append(escape(platform.id()))
                    .append("\" arch=\"").append(escape(platform.arch()))
                    .append("\" os=\"").append(escape(platform.os())).append("\"/>\n");
        }
        out.append("  </platforms>\n");
        out.append("  <modules>\n");
        for (KavaFile.ModuleRef module : modules) {
            out.append("    <module name=\"").append(escape(module.name()))
                    .append("\" path=\"").append(escape(module.path().toString())).append("\"/>\n");
        }
        out.append("  </modules>\n");
        out.append("  <plugins>\n");
        for (KavaFile.PluginRef plugin : plugins) {
            out.append("    <plugin id=\"").append(escape(plugin.id())).append("\"");
            if (plugin.path() != null) {
                out.append(" path=\"").append(escape(plugin.path().toString())).append("\"");
            }
            out.append("/>\n");
        }
        out.append("  </plugins>\n");
        out.append("  <bindings>\n");
        for (KavaFile.Binding binding : bindings) {
            out.append("    <binding");
            if (binding.module() != null) {
                out.append(" module=\"").append(escape(binding.module())).append("\"");
            }
            if (binding.sources() != null) {
                out.append(" sources=\"").append(escape(binding.sources())).append("\"");
            }
            if (binding.artifact() != null) {
                out.append(" artifact=\"").append(escape(binding.artifact())).append("\"");
            }
            out.append(" plugin=\"").append(escape(binding.plugin())).append("\"/>\n");
        }
        out.append("  </bindings>\n");
        out.append("  <goals>\n");
        for (KavaFile.Goal goal : goals) {
            out.append("    <goal name=\"").append(escape(goal.name()));
            if (goal.plugin() != null) out.append("\" plugin=\"").append(escape(goal.plugin()));
            if (goal.suite() != null) out.append("\" suite=\"").append(escape(goal.suite()));
            out.append("\" default=\"").append(goal.isDefault()).append("\"/>\n");
        }
        out.append("  </goals>\n");
        out.append("</project>\n");
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void copyTree(Path from, Path to, boolean skipBuildOutput) throws IOException {
        if (!Files.exists(from)) throw new BuildException("missing split source: " + from);
        try (var walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path relative = from.relativize(source);
                if (skipBuildOutput && skip(relative)) continue;
                Path destination = to.resolve(relative.toString());
                if (Files.isDirectory(source)) Files.createDirectories(destination);
                else if (Files.isRegularFile(source)) copyFile(source, destination);
            }
        }
        Files.createDirectories(to);
    }

    private static boolean skip(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("target") || name.equals("out") || name.equals(".git")) return true;
        }
        return false;
    }

    private static void copyFile(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void writeSha(Path file) throws IOException {
        Files.writeString(Path.of(file + ".sha256"), Hashing.sha256(file) + "\n",
                StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path entry : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static List<Step> plannedSteps(Path sourceRoot, Path targetDir, Path descriptor,
                                           String moduleDir, String module, String repo,
                                           String visibility) {
        String identity = "mini-mvn";
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(List.of("gh", "api", "repos/" + repo), sourceRoot));
        steps.add(new Step(git(targetDir, identity, "init", "-q", "-b", "main"), targetDir));
        steps.add(new Step(git(targetDir, identity, "add", "-A"), targetDir));
        steps.add(new Step(git(targetDir, identity, "commit", "-m",
                "mini-mvn: split " + module + " into " + repo), targetDir));
        steps.add(new Step(List.of("gh", "repo", "create", repo, "--" + visibility,
                "--source", targetDir.toString(), "--remote", "origin", "--push"), targetDir));
        steps.add(new Step(git(sourceRoot, identity, "status", "--porcelain", "--",
                descriptor.getFileName().toString(), moduleDir), sourceRoot));
        steps.add(new Step(git(sourceRoot, identity, "add", "-A", "--",
                descriptor.getFileName().toString(), moduleDir), sourceRoot));
        steps.add(new Step(git(sourceRoot, identity, "commit", "-m",
                "mini-mvn: remove " + module + " after split"), sourceRoot));
        steps.add(new Step(git(sourceRoot, identity, "push", "-u", "origin", "main"), sourceRoot));
        return steps;
    }

    private static void publish(Result result, String repo, String visibility, String module,
                                boolean verbose) throws IOException {
        Path sourceRoot = result.sourceRoot();
        Path targetDir = result.targetDir();
        String login = "mini-mvn";
        Exec.Result user = Exec.run(List.of("gh", "api", "user", "--jq", ".login"),
                sourceRoot, null, verbose);
        if (user.ok() && !user.output().isBlank()) {
            login = user.output().trim().lines().findFirst().orElse("mini-mvn");
        }

        Exec.Result existing = Exec.run(List.of("gh", "api", "repos/" + repo),
                sourceRoot, null, verbose);
        boolean exists = existing.ok() && existing.output().contains("\"full_name\"");

        if (!Files.isDirectory(targetDir.resolve(".git"))) {
            runGit(targetDir, login, verbose, "init", "-q", "-b", "main");
        }
        runGit(targetDir, login, verbose, "add", "-A");
        runGit(targetDir, login, verbose, "commit", "-m",
                "mini-mvn: split " + module + " into " + repo);
        if (exists) {
            Exec.Result remote = Exec.run(git(targetDir, login, "remote", "get-url", "origin"),
                    targetDir, null, verbose);
            if (!remote.ok()) {
                runGit(targetDir, login, verbose, "remote", "add", "origin",
                        "https://github.com/" + repo + ".git");
            }
            runGit(targetDir, login, verbose, "push", "-u", "origin", "main");
        } else {
            Exec.Result created = Exec.run(List.of("gh", "repo", "create", repo, "--" + visibility,
                    "--source", targetDir.toString(), "--remote", "origin", "--push"),
                    targetDir, gitEnv(targetDir), verbose);
            if (!created.ok()) {
                throw new IOException("gh repo create " + repo + " failed:\n" + created.output());
            }
        }

        String sourceModule = sourceRoot.relativize(result.sourceModuleDir()).toString();
        Exec.Result status = Exec.run(git(sourceRoot, login, "status", "--porcelain", "--",
                result.sourceDescriptor().getFileName().toString(), sourceModule),
                sourceRoot, null, verbose);
        if (status.ok() && !status.output().isBlank()) {
            runGit(sourceRoot, login, verbose, "add", "-A", "--",
                    result.sourceDescriptor().getFileName().toString(), sourceModule);
            runGit(sourceRoot, login, verbose, "commit", "-m",
                    "mini-mvn: remove " + module + " after split");
        }
        String branch = branchOf(sourceRoot, login, verbose);
        runGit(sourceRoot, login, verbose, "push", "-u", "origin", branch);
        System.out.println("mini-mvn: split " + module + " -> " + repo);
    }

    private static String branchOf(Path dir, String login, boolean verbose) throws IOException {
        Exec.Result result = Exec.run(git(dir, login, "branch", "--show-current"),
                dir, null, verbose);
        String branch = result.output().trim().lines().findFirst().orElse("");
        return branch.isEmpty() ? "main" : branch;
    }

    private static Map<String, String> gitEnv(Path dir) {
        return Map.of(
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "safe.directory",
                "GIT_CONFIG_VALUE_0", dir.toAbsolutePath().normalize().toString());
    }

    private static List<String> git(Path dir, String login, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=" + dir.toAbsolutePath().normalize());
        command.add("-c");
        command.add("user.name=" + login);
        command.add("-c");
        command.add("user.email=" + login + "@users.noreply.github.com");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        return command;
    }

    private static void runGit(Path dir, String login, boolean verbose, String... args)
            throws IOException {
        run(git(dir, login, args), dir, verbose);
    }

    private static void run(List<String> command, Path dir, boolean verbose) throws IOException {
        Exec.Result result = Exec.run(command, dir, null, verbose);
        if (!result.ok()) {
            throw new IOException(String.join(" ", command) + " failed:\n" + result.output());
        }
    }
}
