package mini.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mini.maven.engine.BuildException;
import mini.maven.engine.BuiltinPlugins;
import mini.maven.engine.Engine;
import mini.maven.engine.Exec;
import mini.maven.engine.HashManifest;
import mini.maven.engine.PomWriter;
import mini.maven.engine.ProjectSplitter;
import mini.maven.engine.RemoteResolver;
import mini.maven.model.KavaFile;
import mini.maven.model.ModuleFile;
import mini.maven.model.PluginFile;
import mini.maven.model.RioFile;
import mini.maven.model.StateFile;
import mini.maven.xml.XmlException;

public final class Main {

    public static final String VERSION = "0.1";
    private static final String ENGINE = "mini-mvn " + VERSION;

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (UsageException e) {
            System.err.println("mini-mvn: " + e.getMessage());
            System.err.println("run 'mini-mvn --help' for usage");
            code = 2;
        } catch (BuildException e) {
            System.err.println("mini-mvn: " + e.getMessage());
            code = 1;
        } catch (XmlException e) {
            System.err.println("mini-mvn: " + e.getMessage());
            code = 1;
        } catch (Throwable t) {
            System.err.println("mini-mvn: internal error: " + t);
            t.printStackTrace(System.err);
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")
                || args[0].equals("help")) {
            System.out.print(usage());
            return 0;
        }

        String command = args[0];
        if (command.equals("split")) {
            return splitCommand(args);
        }
        if (command.equals("run")) {
            return runCommand(args);
        }
        if (command.equals("dist")) {
            return distProgram(args);
        }
        if (command.equals("verify")) {
            return verifyCommand(args);
        }
        if (command.equals("dependencies")) {
            return dependenciesCommand(args);
        }
        if (command.equals("publish")) {
            return publishCommand(args);
        }
        Options options = Options.parse(args, 1);
        Path root = options.file == null ? Path.of("kava.xml") : options.file;
        root = root.toAbsolutePath().normalize();
        Path projectDir = root.getParent();

        KavaFile project = KavaFile.load(root);
        return switch (command) {
            case "build" -> build(project, projectDir, options);
            case "install" -> install(project, projectDir, options);
            case "test" -> test(project, projectDir, options);
            case "doctor" -> doctor(project, projectDir, options);
            case "clean" -> clean(project, projectDir, options);
            default -> throw new UsageException("unknown command: " + command);
        };
    }

    /**
     * {@code mini-mvn run <project-dir> [-- args...]}: compile a manifest-based program with
     * the mini-compiler and execute the produced native binary.
     *
     * <p>This runs a <em>sample/user program</em> described by a {@code program.manifest}
     * (as under {@code project/}), not the toolchain modules. It always: (1) builds the
     * compiler and native runtime incrementally, so the compile can link; (2) runs
     * {@code mini.MiniCompiler --manifest <dir>/program.manifest --emit=exe}; and (3)
     * executes the produced binary with the terminal's stdin/stdout/stderr attached.
     * Arguments after a bare {@code --} are passed through to the program.
     *
     * <p>The command's exit code is the program's own exit code.
     */
    private static int runProgram(String[] args) throws IOException {
        // args[1] is the project dir (optional, defaults to '.'); everything after a bare
        // "--" is the program's own arguments.
        List<String> programArgs = new ArrayList<>();
        String dirArg = null;
        boolean verbose = false;
        String runtimeOverride = null;
        int sep = -1;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--")) { sep = i; break; }
        }
        int limit = sep < 0 ? args.length : sep;
        for (int i = 1; i < limit; i++) {
            if (args[i].equals("--verbose") || args[i].equals("-v")) {
                verbose = true;
                continue;
            }
            if (args[i].equals("--runtime") && i + 1 < limit) {
                runtimeOverride = args[i + 1];
                i++;
                continue;
            }
            if (!args[i].startsWith("-") && dirArg == null) {
                dirArg = args[i];
                continue;
            }
            throw new UsageException("run takes a single project directory; unexpected '"
                    + args[i] + "'");
        }
        if (sep >= 0) for (int i = sep + 1; i < args.length; i++) programArgs.add(args[i]);

        Path programDir = Path.of(dirArg == null ? "." : dirArg).toAbsolutePath().normalize();
        Path manifest = programDir.resolve("program.manifest");
        if (!Files.isRegularFile(manifest)) {
            throw new UsageException("no program.manifest in " + programDir
                    + "\n  usage: mini-mvn run <project-dir> [-- args...]");
        }

        // The toolchain must exist to compile and link the program. Find the repository
        // (the kava.xml above this program dir) and build the compiler + runtime first
        // (incremental: a no-op when already built).
        Path root = findKavaXml(programDir);
        if (root == null) {
            throw new UsageException("cannot find kava.xml above " + programDir
                    + "\n  run from inside the mini-compiler repository");
        }
        Path projectDir = root.getParent();
        KavaFile project = KavaFile.load(root);
        Overview overview = loadAll(project, projectDir);
        System.out.println(ENGINE + " run " + display(projectDir, programDir));
        Engine engine = new Engine(projectDir, project, overview.modules, overview.plugins,
                null, true, null, verbose, 1);
        engine.build();

        // Compile the program from its manifest to a native executable.
        Path compilerJar = projectDir.resolve("target/compiler/mini-compiler-all.jar");
        if (!Files.isRegularFile(compilerJar)) {
            throw new BuildException("compiler jar not found at " + compilerJar
                    + " (build failed?)");
        }
        List<String> compile = new ArrayList<>();
        compile.add(javaHome() + "/bin/java");
        compile.add("-jar");
        compile.add(compilerJar.toString());
        compile.add("--manifest");
        compile.add(manifest.toString());
        compile.add("--emit=exe");
        if (runtimeOverride != null) {
            compile.add("--runtime");
            compile.add(runtimeOverride);
        }
        Path runtimeArchive = runtimeArchive(projectDir);
        if (Files.isRegularFile(runtimeArchive)) {
            compile.add("--runtime-lib");
            compile.add(runtimeArchive.getParent().toString());
        }
        Map<String, String> env = new HashMap<>();
        env.put("MINI_CORELIB", projectDir.resolve("corelib").toString());
        mini.maven.engine.Exec.Result r = mini.maven.engine.Exec.run(compile, programDir, env, verbose);
        if (!r.ok()) {
            throw new BuildException("compiling " + display(projectDir, programDir)
                    + " failed:\n" + r.output());
        }

        // Locate the produced binary: <out>/<simple name of main>. The compiler names the
        // executable after the entry class, so read 'main' and 'out' from the manifest.
        Map<String, String> keys = readManifestKeys(manifest);
        String mainClass = keys.get("main");
        if (mainClass == null) {
            throw new UsageException(manifest + ": no 'main:' key, cannot locate the binary");
        }
        String outDir = keys.getOrDefault("out", "out");
        String simpleName = mainClass.contains(".")
                ? mainClass.substring(mainClass.lastIndexOf('.') + 1) : mainClass;
        Path binary = programDir.resolve(outDir).resolve(simpleName);
        if (!Files.isRegularFile(binary)) {
            throw new BuildException("expected a binary at " + binary + " but none was produced");
        }

        // Execute it with the terminal attached, forwarding the program's own arguments.
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.toString());
        cmd.addAll(programArgs);
        System.out.println(ENGINE + ": running " + display(projectDir, binary)
                + (programArgs.isEmpty() ? "" : " " + String.join(" ", programArgs)));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO().directory(programDir.toFile());
        try {
            Process process = pb.start();
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running " + binary, e);
        }
    }

    private static int runCommand(String[] args) throws IOException {
        RunOptions options = RunOptions.parse(args);
        if (options.file != null) {
            if (options.file.endsWith("kava.xml")) return runProgram(args);
            return runRio(options);
        }
        if (options.spec == null) {
            return runProgram(args);
        }
        Path local = Path.of(options.spec);
        if (local.isAbsolute() || options.spec.startsWith(".") || Files.isDirectory(local)) {
            return runProgram(args);
        }
        return runRio(options);
    }

    private static int runRio(RunOptions options) throws IOException {
        String spec = options.ref == null ? options.spec : options.spec + "@" + options.ref;
        RioProject project = loadRioProject(spec,
                options.file == null ? null : Path.of(options.file), options.verbose);
        RioFile rio = project.descriptor();
        Path projectDir = project.dir();
        String repository = project.repository();

        RioFile.Resolution resolution;
        try {
            resolution = rio.resolve(options.platform, options.profile);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        String platform = options.platform == null ? rio.defaultPlatform() : options.platform;
        String profile = options.profile == null ? "default" : options.profile;
        System.out.println(ENGINE + " run " + repository);
        System.out.println("platform " + platform + " profile " + profile
                + " runtime " + resolution.runtime());

        Map<String, String> env = new HashMap<>();
        env.put("JAVA_HOME", javaHome());
        for (RioFile.Dependency dependency : rio.dependencies()) {
            if (dependency.kind().equals("engine")) {
                System.out.println("dependency engine " + dependency.repository()
                        + " (" + dependency.ref() + ")");
                continue;
            }
            if (!dependency.kind().equals("kava-dist")) continue;
            if (resolution.runtime().equals("java")) {
                System.out.println("dependency kava-dist " + dependency.repository()
                        + " (" + dependency.ref() + ") skipped for java runtime");
                continue;
            }
            RemoteResolver.Resolved tool = RemoteResolver.resolveSource(
                    dependency.repository() + "@" + dependency.ref(), options.verbose);
            Path prefix = prefixFor(tool);
            ensureKavaDist(tool, prefix, env, options.verbose);
            System.out.println("dependency kava-dist " + tool.repository()
                    + "@" + tool.sha() + " -> " + prefix);
        }

        String port = options.port == null ? rio.run().port() : options.port;
        String workspaceValue = options.workspace == null
                ? rio.run().workspace() : options.workspace;
        Path workspace = Path.of(workspaceValue);
        if (!workspace.isAbsolute()) workspace = projectDir.resolve(workspace).normalize();

        if (resolution.runtime().equals("java")) {
            Exec.Result build = Exec.run(List.of("bash", "build.sh"), projectDir, env,
                    options.verbose);
            if (!build.ok()) {
                throw new BuildException("building " + repository + " failed:\n" + build.output());
            }
        } else {
            String prefix = env.get("KAVA_HOME");
            if (prefix == null) {
                throw new BuildException("native profile needs a kava-dist dependency in rio.xml");
            }
            env.put("GC", resolution.gc());
            List<String> build = new ArrayList<>();
            build.add("sh");
            build.add("./mini-rio");
            build.add("build");
            build.add("--prefix");
            build.add(prefix);
            build.add("--target");
            build.add(resolution.target());
            if (resolution.staticLink()) build.add("--static");
            if (resolution.cc() != null) {
                build.add("--cc");
                build.add(resolution.cc());
            }
            Exec.Result result = Exec.run(build, projectDir, env, options.verbose);
            if (!result.ok()) {
                throw new BuildException("building native " + repository
                        + " failed:\n" + result.output());
            }
        }

        verifyDeclaredArtifacts(rio, projectDir);
        writeRuntimeManifest(projectDir);

        List<String> command = new ArrayList<>();
        Path nativeLauncher = null;
        if (resolution.runtime().equals("native")) {
            Path binary = projectDir.resolve("out").resolve("rio-server");
            if (!Files.isRegularFile(binary)) {
                throw new BuildException("native binary not found at " + binary);
            }
            nativeLauncher = Files.createTempDirectory("mini-mvn-rio-").resolve("rio-server");
            Files.copy(binary, nativeLauncher, StandardCopyOption.REPLACE_EXISTING);
            if (!nativeLauncher.toFile().setExecutable(true)) {
                throw new IOException("cannot make native launcher executable: " + nativeLauncher);
            }
        }
        if (resolution.runtime().equals("java")) {
            command.add(Path.of(javaHome()).resolve("bin").resolve("java").toString());
            command.add("-cp");
            command.add("out");
            command.add(rio.run().main());
            command.add(port);
            command.add(rio.run().browser());
            command.add(workspace.toString());
            command.add(System.getenv("RIO_PASSCODE") == null ? "" : System.getenv("RIO_PASSCODE"));
            command.add(System.getenv("RIO_WS_LOG") == null ? "rio-ws.log"
                    : System.getenv("RIO_WS_LOG"));
        } else {
            command.add(nativeLauncher.toString());
            command.add("--");
            command.add(port);
            command.add(rio.run().browser());
            command.add(workspace.toString());
            command.add("");
            command.add(System.getenv("RIO_WS_LOG") == null ? "rio-ws.log"
                    : System.getenv("RIO_WS_LOG"));
        }
        System.out.println(ENGINE + ": running " + repository + " on port " + port);
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO().directory(projectDir.toFile());
        builder.environment().putAll(env);
        try {
            return builder.start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running " + repository, e);
        } finally {
            if (nativeLauncher != null) {
                Files.deleteIfExists(nativeLauncher);
                Files.deleteIfExists(nativeLauncher.getParent());
            }
        }
    }

    private static Path prefixFor(RemoteResolver.Resolved tool) {
        return RemoteResolver.cacheRoot().resolve("kava").resolve(tool.sha());
    }

    private static void ensureKavaDist(RemoteResolver.Resolved tool, Path prefix,
                                       Map<String, String> env, boolean verbose)
            throws IOException {
        Path marker = prefix.resolve(".mini-mvn-install");
        if (Files.isRegularFile(marker)
                && markerBuildMatches(marker, tool.sha())
                && Files.isRegularFile(prefix.resolve("bin").resolve("mini-compiler-all.jar"))) {
            env.put("KAVA_HOME", prefix.toString());
            env.put("MINI_HOME", prefix.toString());
            env.put("MINI_CORELIB", prefix.resolve("corelib").toString());
            return;
        }
        Path launcher = Path.of(System.getProperty("mini.mvn.home", "."))
                .toAbsolutePath().normalize().resolve("mini-mvn");
        if (!Files.isRegularFile(launcher)) {
            throw new BuildException("mini-mvn launcher not found at " + launcher);
        }
        Exec.Result result = Exec.run(List.of("sh", launcher.toString(), "install",
                "--to", prefix.toString()), tool.dir(), env, verbose);
        if (!result.ok()) {
            throw new BuildException("installing kava-dist failed:\n" + result.output());
        }
        Files.createDirectories(prefix);
        Files.writeString(marker, tool.sha(), StandardCharsets.UTF_8);
        env.put("KAVA_HOME", prefix.toString());
        env.put("MINI_HOME", prefix.toString());
        env.put("MINI_CORELIB", prefix.resolve("corelib").toString());
    }

    private static boolean markerBuildMatches(Path marker, String sha) throws IOException {
        return Files.readString(marker, StandardCharsets.UTF_8).trim().equals(sha);
    }

    private static void verifyDeclaredArtifacts(RioFile rio, Path projectDir) throws IOException {
        for (RioFile.Artifact artifact : rio.artifacts()) {
            if (artifact.sha256() == null) continue;
            Path file = projectDir.resolve(artifact.path());
            if (!Files.isRegularFile(file)) {
                throw new BuildException("declared artifact is missing: " + artifact.path());
            }
            String actual = mini.maven.engine.Hashing.sha256(file);
            if (!actual.equals(artifact.sha256())) {
                throw new BuildException("hash mismatch for " + artifact.path()
                        + ": expected " + artifact.sha256() + ", got " + actual);
            }
        }
    }

    private static void writeRuntimeManifest(Path projectDir) throws IOException {
        List<Path> files = new ArrayList<>();
        addFiles(projectDir.resolve("out"), files);
        addFiles(projectDir.resolve("rio-browser"), files);
        HashManifest.write(projectDir, files, projectDir.resolve("target")
                .resolve(".mini-maven").resolve("artifacts.sha256"));
    }

    private static void addFiles(Path root, List<Path> files) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
    }

    /** Walk up from {@code dir} to the first directory containing a {@code kava.xml}. */
    private static Path findKavaXml(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path candidate = d.resolve("kava.xml");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** Minimal reader for a manifest's scalar keys (only what {@code run} needs). */
    private static Map<String, String> readManifestKeys(Path manifest) throws IOException {
        Map<String, String> keys = new HashMap<>();
        for (String raw : Files.readAllLines(manifest)) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            // a two-space-indented line is a list continuation, not a key
            if (!line.isEmpty() && Character.isWhitespace(line.charAt(0))) continue;
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!value.isEmpty()) keys.putIfAbsent(key, value);
        }
        return keys;
    }

    private static int build(KavaFile project, Path projectDir, Options options)
            throws IOException {
        Overview overview = loadAll(project, projectDir);
        if (options.module != null && project.module(options.module) == null) {
            throw new UsageException("unknown module: " + options.module);
        }
        System.out.println(ENGINE);
        System.out.println("project " + project.name()
                + (project.version() == null ? "" : " " + project.version()));
        Engine engine = new Engine(projectDir, project, overview.modules, overview.plugins,
                options.module, options.withDeps, options.target, options.verbose,
                options.jobs);
        engine.build();
        return 0;
    }

    /**
     * Build, then stage the produced artifacts under an install prefix (default
     * {@code dist/}, overridable with {@code --to}). Jar artifacts land in
     * {@code <prefix>/bin}; the per-platform native libraries ({@code .a}/{@code .so}) land
     * in {@code <prefix>/lib/<triple>}. The copy is offline and in-tree, matching the rest
     * of the build — it does not touch {@code ~/.m2} or any system location.
     */
    private static int install(KavaFile project, Path projectDir, Options options)
            throws IOException {
        Overview overview = loadAll(project, projectDir);
        if (options.module != null && project.module(options.module) == null) {
            throw new UsageException("unknown module: " + options.module);
        }
        System.out.println(ENGINE);
        System.out.println("project " + project.name()
                + (project.version() == null ? "" : " " + project.version()));
        Engine engine = new Engine(projectDir, project, overview.modules, overview.plugins,
                options.module, options.withDeps, options.target, options.verbose,
                options.jobs);
        engine.build();

        Path prefix = (options.to == null ? projectDir.resolve("dist") : options.to)
                .toAbsolutePath().normalize();
        Path bin = prefix.resolve("bin");
        Path target = projectDir.resolve("target");
        int staged = 0;

        // Jar artifacts -> <prefix>/bin/<id>.jar
        for (ModuleFile module : overview.modules.values()) {
            if (options.module != null && !module.name().equals(options.module)) continue;
            for (ModuleFile.Artifact artifact : module.artifacts()) {
                boolean jarLike = artifact.type().equals("jar")
                        || artifact.type().equals("uberjar");
                if (!jarLike || artifact.tests()) continue;
                Path jar = target.resolve(module.name()).resolve(artifact.id() + ".jar");
                staged += stage(jar, bin.resolve(artifact.id() + ".jar"), projectDir);
            }
        }

        // Native libraries -> <prefix>/lib/<triple>/<id>
        for (var platform : project.platforms()) {
            if (options.target != null && !options.target.equals(platform.id())) continue;
            Path from = target.resolve(platform.id());
            if (!Files.isDirectory(from)) continue;
            Path libDir = prefix.resolve("lib").resolve(platform.id());
            for (ModuleFile module : overview.modules.values()) {
                if (options.module != null && !module.name().equals(options.module)) continue;
                for (ModuleFile.Artifact artifact : module.artifacts()) {
                    if (!artifact.type().equals("archive") && !artifact.type().equals("shared")) {
                        continue;
                    }
                    staged += stage(from.resolve(artifact.id()),
                            libDir.resolve(artifact.id()), projectDir);
                }
            }
        }

        // The compiler compiles the corelib from source at build time and reads a rule file
        // at link time, so a relocatable prefix must also carry those. Stage the corelib
        // source tree under <prefix>/corelib and the rule files under <prefix>/rules; the
        // compiler's install-prefix discovery then needs no repository checkout.
        if (options.module == null || "corelib".equals(options.module)) {
            Path corelibSrc = corelibSourceDir(projectDir, overview.modules.get("corelib"));
            if (corelibSrc != null && Files.isDirectory(corelibSrc)) {
                staged += stageTree(corelibSrc, prefix.resolve("corelib"), projectDir);
            }
            Path rules = projectDir.resolve("rules");
            if (Files.isDirectory(rules)) {
                staged += stageTree(rules, prefix.resolve("rules"), projectDir);
            }
        }

        if (staged == 0) {
            System.out.println("mini-mvn: nothing to install (build the artifacts first)");
        } else {
            System.out.println("mini-mvn: installed " + staged + " artifact(s) to "
                    + display(projectDir, prefix));
        }
        return 0;
    }

    private static void cleanDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            var paths = walk.sorted((a,b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : paths) {
                if (p.equals(dir)) continue;
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String targetTriple(Path repoDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("uname", "-m");
            pb.directory(repoDir.toFile());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0) {
                if (out.contains("aarch64") || out.contains("arm64")) return "aarch64-linux-gnu";
            }
        } catch (Exception ignored) {}
        return "x86_64-linux-gnu";
    }

    private static Path findSystemLib(String name) {
        String[] dirs = {
                "/usr/lib/x86_64-linux-gnu", "/lib/x86_64-linux-gnu",
                "/usr/lib64", "/lib64", "/usr/lib", "/lib"
        };
        for (String d : dirs) {
            Path p = Path.of(d).resolve(name);
            if (Files.exists(p)) return p;
            Path dir = Path.of(d);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.newDirectoryStream(dir, name.replace(".so", ".so*"))) {
                    for (Path candidate : stream) {
                        if (Files.isRegularFile(candidate)) return candidate;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** The corelib module's first java source directory ({@code corelib/src}), or null. */
    private static Path corelibSourceDir(Path projectDir, ModuleFile corelib) {
        if (corelib == null) return null;
        for (ModuleFile.SourceSet set : corelib.sources("java")) return set.dir();
        return null;
    }

    /**
     * Copy a whole directory tree into {@code to}, mirroring its structure. Returns 1 when
     * at least one file was staged, 0 when the source held none. Used to stage the corelib
     * source and the rule files into a relocatable install prefix.
     */
    private static int stageTree(Path from, Path to, Path projectDir) throws IOException {
        int[] count = {0};
        try (var walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(src)) continue;
                Path dest = to.resolve(from.relativize(src).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                count[0]++;
            }
        }
        if (count[0] > 0) {
            System.out.println("mini-mvn: installed " + display(projectDir, to) + "/ ("
                    + count[0] + " files)");
            return 1;
        }
        return 0;
    }

    /** Copy one artifact if it exists; returns 1 when staged, 0 when the source is missing. */
    private static int stage(Path from, Path to, Path projectDir) throws IOException {
        if (!Files.isRegularFile(from)) return 0;
        Files.createDirectories(to.getParent());
        Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("mini-mvn: installed " + display(projectDir, to));
        return 1;
    }

    /** A path shown relative to the project when it lives inside it, absolute otherwise. */
    private static String display(Path projectDir, Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return abs.startsWith(projectDir) ? projectDir.relativize(abs).toString()
                : abs.toString();
    }

    private static int test(KavaFile project, Path projectDir, Options options)
            throws IOException {
        Overview overview = loadAll(project, projectDir);
        String wanted = options.suite;
        if (wanted == null) {
            var goal = project.goal("test");
            wanted = goal != null && goal.suite() != null ? goal.suite() : "smoke";
        }
        List<SuiteHit> hits = new ArrayList<>();
        for (ModuleFile module : overview.modules.values()) {
            var suite = module.suite(wanted);
            if (suite != null) hits.add(new SuiteHit(module, suite));
        }
        if (hits.isEmpty()) {
            Set<String> names = new LinkedHashSet<>();
            for (ModuleFile module : overview.modules.values()) {
                for (var suite : module.suites()) names.add(suite.name());
            }
            throw new UsageException("no suite named '" + wanted + "'; known suites: "
                    + join(new ArrayList<>(names)));
        }
        System.out.println(ENGINE + " test --suite " + wanted);

        // Ensure modules needed for testing are built
        Engine engine = new Engine(projectDir, project, overview.modules, overview.plugins,
                null, true, options.target, options.verbose, options.jobs);
        try {
            engine.build();
        } catch (Exception e) {
            // continue if build has issues but classes might exist
        }

        for (SuiteHit hit : hits) {
            runSuite(projectDir, hit, wanted, options.verbose);
        }
        return 0;
    }

    private static void runSuite(Path projectDir, SuiteHit hit,
                                 String suiteName, boolean verbose) throws IOException {
        ModuleFile module = hit.module;
        Path target = projectDir.resolve("target").resolve(module.name());
        Path classes = target.resolve("classes");
        Path testClasses = target.resolve("test-classes");
        if (!Files.isDirectory(classes) || !Files.isDirectory(testClasses)) {
            throw new UsageException("compiler test classes not found; run 'mini-mvn build' first");
        }

        List<Path> cp = new ArrayList<>();
        cp.add(testClasses);
        cp.add(classes);
        cp.addAll(moduleTestClasspath(projectDir, module));
        String classpath = joinPaths(cp);

        Path reports = projectDir.resolve("target/test-reports");
        Files.createDirectories(reports);

        List<String> classNames = collectSuiteClasses(testClasses, hit.suite);
        if (classNames.isEmpty()) {
            throw new UsageException("no test classes found for suite " + suiteName);
        }

        Path runner = projectDir.resolve("target/.mini-maven/JunitRun.java");
        Files.createDirectories(runner.getParent());
        StringBuilder runnerSrc = new StringBuilder();
        runnerSrc.append("public class JunitRun {\n");
        runnerSrc.append("  public static void main(String[] args) throws Exception {\n");
        runnerSrc.append("    org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder builder = org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request();\n");
        for (String cn : classNames) {
            runnerSrc.append("    builder.selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(\"" + cn + "\"));\n");
        }
        runnerSrc.append("    org.junit.platform.launcher.LauncherDiscoveryRequest request = builder.build();\n");
        runnerSrc.append("    org.junit.platform.launcher.Launcher launcher = org.junit.platform.launcher.core.LauncherFactory.create();\n");
        runnerSrc.append("    org.junit.platform.launcher.listeners.SummaryGeneratingListener listener = new org.junit.platform.launcher.listeners.SummaryGeneratingListener();\n");
        runnerSrc.append("    launcher.registerTestExecutionListeners(listener);\n");
        runnerSrc.append("    launcher.execute(request);\n");
        runnerSrc.append("    org.junit.platform.launcher.listeners.TestExecutionSummary summary = listener.getSummary();\n");
        runnerSrc.append("    summary.printTo(new java.io.PrintWriter(System.out));\n");
        runnerSrc.append("    summary.printFailuresTo(new java.io.PrintWriter(System.out));\n");
        runnerSrc.append("    System.exit(summary.getTestsFailedCount() > 0 ? 1 : 0);\n");
        runnerSrc.append("  }\n");
        runnerSrc.append("}\n");
        Files.writeString(runner, runnerSrc.toString());

        Path runnerClass = projectDir.resolve("target/.mini-maven/junit-run");
        Files.createDirectories(runnerClass);
        List<String> javacCmd = new ArrayList<>();
        javacCmd.add(javaHome() + "/bin/javac");
        javacCmd.add("--release");
        javacCmd.add("17");
        javacCmd.add("-cp");
        javacCmd.add(classpath);
        javacCmd.add("-d");
        javacCmd.add(runnerClass.toString());
        javacCmd.add(runner.toString());
        mini.maven.engine.Exec.Result r1 = mini.maven.engine.Exec.run(javacCmd, projectDir, null, verbose);
        if (!r1.ok()) throw new BuildException("failed to compile test runner:\n" + r1.output());

        List<String> javaCmd = new ArrayList<>();
        javaCmd.add(javaHome() + "/bin/java");
        javaCmd.add("-cp");
        javaCmd.add(runnerClass.toString() + java.io.File.pathSeparator + classpath);
        javaCmd.add("-Dkava.root=" + projectDir);
        javaCmd.add("-DMINI_CORELIB=" + projectDir.resolve("corelib"));
        Path runtimeArchive = runtimeArchive(projectDir);
        if (Files.isRegularFile(runtimeArchive)) {
            javaCmd.add("-Dmini.runtime.archive=" + runtimeArchive);
        }
        javaCmd.add("JunitRun");
        mini.maven.engine.Exec.Result r2 = mini.maven.engine.Exec.run(javaCmd, projectDir, null, true);
        if (!r2.ok()) throw new BuildException("tests failed for suite " + suiteName);
    }

    private static List<String> collectSuiteClasses(Path testClasses, ModuleFile.Suite suite) throws IOException {
        List<String> result = new ArrayList<>();
        try (var walk = Files.walk(testClasses)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> {
                    String rel = testClasses.relativize(p).toString();
                    if (rel.contains("$")) return;
                    String cn = rel.replace('/', '.').replace('\\', '.').replace(".class", "");
                    if (suite.contains(cn)) result.add(cn);
                });
        }
        result.sort(String::compareTo);
        return result;
    }

    private static List<Path> moduleTestClasspath(Path projectDir, ModuleFile module) {
        List<Path> cp = new ArrayList<>();
        // Derive the spawn classpath from the module's declared dependencies so it tracks the
        // vendored versions exactly — a hardcoded list silently drops a jar whose version
        // drifts, leaving the spawned tests missing classes.
        for (ModuleFile.Dependency dependency : module.dependencies()) {
            if (dependency.isModule()) continue;
            Path jar = vendored(projectDir, dependency);
            if (Files.isRegularFile(jar)) cp.add(jar);
        }
        return cp;
    }

    private static Path runtimeArchive(Path projectDir) {
        String triple = System.getProperty("os.arch").equals("aarch64") ? "aarch64-linux-gnu" : "x86_64-linux-gnu";
        Path p = projectDir.resolve("target").resolve(triple).resolve("libkava.a");
        if (Files.isRegularFile(p)) return p;
        // try common
        if (Files.isRegularFile(projectDir.resolve("target/aarch64-linux-gnu/libkava.a"))) return projectDir.resolve("target/aarch64-linux-gnu/libkava.a");
        if (Files.isRegularFile(projectDir.resolve("target/x86_64-linux-gnu/libkava.a"))) return projectDir.resolve("target/x86_64-linux-gnu/libkava.a");
        return p;
    }

    private static String javaHome() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isEmpty()) return jh;
        String home = System.getProperty("mini.mvn.home");
        if (home != null && !home.isBlank()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh",
                        Path.of(home).resolve("scripts").resolve("find-jdk17.sh").toString());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String line;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    line = reader.readLine();
                }
                if (process.waitFor() == 0 && line != null && !line.isBlank()) {
                    return line.trim();
                }
            } catch (Exception ignored) {
            }
        }
        String current = System.getProperty("java.home");
        if (current != null && !current.isBlank()) return current;
        throw new BuildException("no JDK 17+ found; export JAVA_HOME or install mini-mvn from "
                + "https://github.com/krasgit/mini-mvn");
    }

    private static String joinPaths(List<Path> paths) {
        return String.join(java.io.File.pathSeparator,
                paths.stream().map(Path::toString).toList());
    }

    private static int doctor(KavaFile project, Path projectDir, Options options)
            throws IOException {
        Overview overview = loadAll(project, projectDir);
        System.out.println(ENGINE + " doctor");
        System.out.println("project root " + projectDir);
        System.out.println("jdk " + javaHome());
        for (var platform : project.platforms()) {
            System.out.println("platform " + platform.id() + " (" + platform.arch() + "/"
                    + platform.os() + ")");
        }
        int problems = 0;
        for (ModuleFile module : overview.modules.values()) {
            for (var dependency : module.dependencies()) {
                if (dependency.isModule()) {
                    if (project.module(dependency.module()) == null) {
                        System.out.println("MISSING module dependency "
                                + module.name() + " -> " + dependency.module());
                        problems++;
                    }
                    continue;
                }
                Path jar = vendored(projectDir, dependency);
                if (!Files.isRegularFile(jar)) {
                    System.out.println("MISSING jar " + dependency.coordinates()
                            + " wanted at " + jar);
                    problems++;
                    continue;
                }
                Path sidecar = Path.of(jar + ".sha256");
                if (!Files.isRegularFile(sidecar)) {
                    System.out.println("MISSING checksum " + sidecar);
                    problems++;
                } else {
                    String expected = Files.readString(sidecar, StandardCharsets.UTF_8).trim()
                            .split("\\s+")[0];
                    String actual = mini.maven.engine.Hashing.sha256(jar);
                    if (!expected.equals(actual)) {
                        System.out.println("BAD checksum " + jar);
                        problems++;
                    }
                }
            }
        }
        for (PluginFile plugin : overview.plugins.values()) {
            for (var tool : plugin.tools()) {
                String path = tool.path();
                if (path.contains("${")) {
                    System.out.println("tool " + plugin.name() + "/" + tool.name()
                            + " = " + path + " (expanded at build time)");
                } else if (looksLikePath(path)) {
                    Path p = Path.of(path);
                    boolean ok = Files.isExecutable(p);
                    System.out.println((ok ? "tool " : "MISSING tool ") + plugin.name() + "/"
                            + tool.name() + " = " + path);
                    if (!ok) problems++;
                } else {
                    System.out.println("tool " + plugin.name() + "/" + tool.name()
                            + " = " + path + (onPath(path) ? "" : " (not on PATH)"));
                    if (!onPath(path)) problems++;
                }
            }
        }
        StateFile state = StateFile.loadOrEmpty(projectDir.resolve("target")
                .resolve(".mini-maven").resolve("state.xml"), ENGINE);
        System.out.println("state " + state.file() + " modules=" + state.modules().size());
        System.out.println(problems == 0 ? "doctor: ok" : "doctor: " + problems + " problem(s)");
        return problems == 0 ? 0 : 1;
    }

    private static int clean(KavaFile project, Path projectDir, Options options)
            throws IOException {
        List<Path> targets = List.of(
                projectDir.resolve("target").resolve(".mini-maven"),
                projectDir.resolve("target").resolve("compiler"),
                projectDir.resolve("target").resolve("test-reports"),
                projectDir.resolve("target").resolve("x86_64-linux-gnu"),
                projectDir.resolve("target").resolve("aarch64-linux-gnu"),
                projectDir.resolve("dist"));
        for (Path target : targets) {
            if (!Files.exists(target)) continue;
            delete(target);
            System.out.println("removed " + projectDir.relativize(target));
        }
        return 0;
    }

    private static void delete(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) delete(entry);
            }
        }
        Files.deleteIfExists(path);
    }

    private static Overview loadAll(KavaFile project, Path projectDir) {
        var modules = new java.util.LinkedHashMap<String, ModuleFile>();
        for (var ref : project.modules()) {
            Path file = projectDir.resolve(ref.path()).resolve("module.xml").normalize();
            if (!Files.isRegularFile(file)) {
                throw new XmlException(file, "missing module descriptor for '" + ref.name() + "'");
            }
            ModuleFile module = ModuleFile.load(file);
            if (!module.name().equals(ref.name())) {
                throw new XmlException(file, "module name '" + module.name()
                        + "' does not match kava.xml entry '" + ref.name() + "'");
            }
            modules.put(module.name(), module);
        }
        var plugins = new java.util.LinkedHashMap<String, PluginFile>();
        for (var ref : project.plugins()) {
            PluginFile plugin;
            if (ref.path() == null) {
                plugin = BuiltinPlugins.get(ref.id());
            } else {
                Path file = projectDir.resolve(ref.path()).resolve("plugin.xml").normalize();
                if (!Files.isRegularFile(file)) {
                    throw new XmlException(file, "missing plugin descriptor for '" + ref.id() + "'");
                }
                plugin = PluginFile.load(file);
            }
            if (!plugin.name().equals(ref.id())) {
                throw new XmlException(projectDir.resolve("kava.xml"), "plugin name '"
                        + plugin.name() + "' does not match kava.xml entry '" + ref.id() + "'");
            }
            plugins.put(plugin.name(), plugin);
        }
        return new Overview(modules, plugins);
    }

    private static Path vendored(Path projectDir, ModuleFile.Dependency dependency) {
        return projectDir.resolve("third-party").resolve("repository")
                .resolve(dependency.group().replace('.', '/'))
                .resolve(dependency.artifact())
                .resolve(dependency.version())
                .resolve(dependency.artifact() + "-" + dependency.version() + ".jar");
    }

    private static boolean looksLikePath(String value) {
        return value.contains("/") || value.startsWith(".");
    }

    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path candidate = Path.of(dir).resolve(tool);
            if (Files.isExecutable(candidate)) return true;
        }
        return false;
    }

    private static String join(List<String> values) {
        return String.join(" ", values);
    }

    private static RioProject loadRioProject(String spec, Path file, boolean verbose)
            throws IOException {
        if (file != null) {
            Path descriptor = file.toAbsolutePath().normalize();
            RioFile rio = RioFile.load(descriptor);
            return new RioProject(descriptor.getParent(), rio, "local:" + descriptor);
        }
        RemoteResolver.Resolved resolved = RemoteResolver.resolve(spec, verbose);
        return new RioProject(resolved.dir(), resolved.descriptor(),
                resolved.repository() + "@" + resolved.sha());
    }

    private static int verifyCommand(String[] args) throws IOException {
        Path file = null;
        Path baseline = null;
        Path compare = null;
        boolean verbose = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--file", "-f" -> file = Path.of(Options.value(args, ++i, args[i - 1]));
                case "--baseline" -> baseline = Path.of(Options.value(args, ++i, args[i - 1]));
                case "--compare" -> compare = Path.of(Options.value(args, ++i, args[i - 1]));
                case "--verbose", "-v" -> verbose = true;
                default -> throw new UsageException("unknown option: " + args[i]);
            }
        }
        if (baseline != null || compare != null) {
            if (baseline == null || compare == null) {
                throw new UsageException("--baseline and --compare must be given together");
            }
            StringBuilder report = new StringBuilder();
            boolean same = HashManifest.compare(baseline, compare, report);
            System.out.print(report);
            System.out.println(same ? "verify: identical" : "verify: different");
            return same ? 0 : 1;
        }

        Path descriptor = file == null ? Path.of("kava.xml") : file;
        Path projectDir = descriptor.toAbsolutePath().getParent();
        if (descriptor.getFileName().toString().endsWith("rio.xml")) {
            RioProject rio = loadRioProject(null, descriptor, verbose);
            writeRuntimeManifest(rio.dir());
            System.out.println("verify: wrote " + display(rio.dir(), rio.dir()
                    .resolve("target/.mini-maven/artifacts.sha256")));
            return 0;
        }

        KavaFile project = KavaFile.load(descriptor.toAbsolutePath().normalize());
        Options options = new Options();
        options.verbose = verbose;
        int checked = doctor(project, projectDir, options);
        if (checked != 0) return checked;
        build(project, projectDir, options);
        test(project, projectDir, options);
        List<Path> artifacts = new ArrayList<>();
        Path target = projectDir.resolve("target");
        Path state = target.resolve(".mini-maven");
        if (Files.isDirectory(target)) {
            try (var walk = Files.walk(target)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> !path.startsWith(state))
                        .sorted()
                        .forEach(artifacts::add);
            }
        }
        Path manifest = HashManifest.write(projectDir, artifacts,
                state.resolve("artifacts.sha256"));
        System.out.println("verify: wrote " + display(projectDir, manifest));
        return 0;
    }

    private static int dependenciesCommand(String[] args) throws IOException {
        String spec = null;
        Path file = null;
        boolean tree = false;
        boolean verbose = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--tree" -> tree = true;
                case "--file", "-f" -> file = Path.of(Options.value(args, ++i, args[i - 1]));
                case "--verbose", "-v" -> verbose = true;
                default -> {
                    if (args[i].startsWith("-")) throw new UsageException("unknown option: " + args[i]);
                    spec = args[i];
                }
            }
        }
        if (!tree) throw new UsageException("dependencies currently requires --tree");
        Path descriptor = file == null ? Path.of("kava.xml") : file;
        if (spec != null || descriptor.getFileName().toString().endsWith("rio.xml")) {
            RioProject rio = loadRioProject(spec, spec == null ? descriptor : null, verbose);
            System.out.println(rio.descriptor().name() + " " + rio.repository());
            for (RioFile.Platform platform : rio.descriptor().platforms()) {
                System.out.println("  +- platform " + platform.name() + " ("
                        + platform.runtime() + (platform.target() == null ? ""
                                : ", " + platform.target()) + ")");
            }
            for (RioFile.Dependency dependency : rio.descriptor().dependencies()) {
                System.out.println("  +- " + dependency.name() + " [" + dependency.kind()
                        + "] " + dependency.repository() + "@" + dependency.ref());
            }
            return 0;
        }

        Path root = descriptor.toAbsolutePath().normalize();
        KavaFile project = KavaFile.load(root);
        Overview overview = loadAll(project, root.getParent());
        System.out.println(project.name() + (project.version() == null
                ? "" : " " + project.version()));
        for (var module : project.modules()) {
            printModuleTree(module.name(), overview, new LinkedHashSet<>(), "  ");
        }
        return 0;
    }

    private static void printModuleTree(String name, Overview overview, Set<String> visiting,
                                        String prefix) {
        String marker = visiting.contains(name) ? " (cycle)" : "";
        System.out.println(prefix + "+- " + name + marker);
        if (!visiting.add(name)) return;
        ModuleFile module = overview.modules().get(name);
        if (module != null) {
            for (ModuleFile.Dependency dependency : module.dependencies()) {
                if (dependency.isModule()) {
                    printModuleTree(dependency.module(), overview, visiting, prefix + "   ");
                } else {
                    System.out.println(prefix + "   +- " + dependency.coordinates()
                            + " [" + dependency.scope() + "]");
                }
            }
        }
        visiting.remove(name);
    }

    private static int publishCommand(String[] args) throws IOException {
        String spec = null;
        Path file = null;
        Path to = null;
        boolean verbose = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--to" -> to = Path.of(Options.value(args, ++i, args[i - 1]));
                case "--file", "-f" -> file = Path.of(Options.value(args, ++i, args[i - 1]));
                case "--verbose", "-v" -> verbose = true;
                default -> {
                    if (args[i].startsWith("-")) throw new UsageException("unknown option: " + args[i]);
                    spec = args[i];
                }
            }
        }
        Path descriptor = file == null ? Path.of("kava.xml") : file;
        Path projectDir = descriptor.toAbsolutePath().getParent();
        Path destination = to == null ? projectDir.resolve("dist").resolve("publish")
                : to.toAbsolutePath().normalize();
        if (spec != null || descriptor.getFileName().toString().endsWith("rio.xml")) {
            RioProject rio = loadRioProject(spec, spec == null ? descriptor : null, verbose);
            for (RioFile.Artifact artifact : rio.descriptor().artifacts()) {
                Path source = rio.dir().resolve(artifact.path());
                Path target = destination.resolve(artifact.id());
                if (Files.isDirectory(source)) stageTree(source, target, rio.dir());
                else stage(source, target, rio.dir());
            }
            PomWriter.write(destination.resolve(rio.descriptor().name() + ".pom"),
                    "mini", rio.descriptor().name(), "0.1");
        } else {
            KavaFile project = KavaFile.load(descriptor.toAbsolutePath().normalize());
            Options options = new Options();
            options.to = destination;
            options.verbose = verbose;
            install(project, projectDir, options);
            PomWriter.write(destination.resolve(project.name() + ".pom"),
                    "mini", project.name(), project.version() == null ? "0.1" : project.version());
        }
        List<Path> staged = new ArrayList<>();
        addFiles(destination, staged);
        for (Path path : staged) {
            if (path.getFileName().toString().endsWith(".sha256")) continue;
            Path sidecar = Path.of(path + ".sha256");
            Files.writeString(sidecar, mini.maven.engine.Hashing.sha256(path)
                    + "  " + destination.relativize(path).toString().replace('\\', '/') + "\n",
                    StandardCharsets.UTF_8);
        }
        addFiles(destination, staged);
        HashManifest.write(destination, staged, destination.resolve("artifacts.sha256"));
        System.out.println("published " + staged.stream()
                .filter(path -> !path.getFileName().toString().endsWith(".sha256"))
                .count() + " file(s) to " + destination);
        return 0;
    }

    private static int splitCommand(String[] args) throws IOException {
        SplitOptions options = SplitOptions.parse(args);
        ProjectSplitter.Result result = ProjectSplitter.split(options.file, options.module,
                options.repo, options.visibility, options.to, options.group, options.force,
                options.dryRun, options.localOnly, options.verbose);
        for (ProjectSplitter.Step step : result.steps()) {
            System.out.println("+ cd " + step.dir() + " && " + String.join(" ", step.args()));
        }
        if (options.localOnly) System.out.println("mini-mvn: local split only, GitHub skipped");
        return 0;
    }

    private static String usage() {
        return """
                mini-mvn %s - the mini-compiler build engine

                usage:
                  mini-mvn split --module M --repo owner/name [--visibility public|private] [--to DIR] [--group G] [--force] [--dry-run] [--local-only]
                  mini-mvn build [--module M] [--with-deps] [--target P] [--file kava.xml]
                  mini-mvn install [--to DIR] [--module M] [--with-deps] [--target P] [--file kava.xml]
                  mini-mvn test --suite smoke|unit|integration|full [--file kava.xml]
                  mini-mvn run [mini-rio|owner/repo] [-platform default|arm|x86] [-profile debug|release|static|dynamic] [--port N] [--workspace DIR] [-- args...]
                  mini-mvn run <project-dir> [--verbose] [-- args...]
                  mini-mvn dist <project-dir> [--to DIR] [--verbose]
                  mini-mvn dependencies --tree [mini-rio|--file rio.xml]
                  mini-mvn verify [--baseline A --compare B] [--file kava.xml|rio.xml]
                  mini-mvn publish [mini-rio|--file descriptor] [--to DIR]
                  mini-mvn doctor [--file kava.xml]
                  mini-mvn clean [--file kava.xml]
                  mini-mvn --help

                options:
                  --file <path>    project file (default: kava.xml)
                  --to <dir>       install/publish/output prefix
                  --module <name>  build one module
                  --with-deps      build the selected module's dependencies first
                  --target <id>    restrict work to one platform id
                  --suite <name>   test suite to run
                  --jobs <n>       parallel jobs (default: 1)
                  --repo <owner/name>  GitHub target for split
                  --visibility <public|private>  target repo visibility (default: public)
                  --group <G>      vendored dependency group for split (default: owner)
                  --force          overwrite an existing split target
                  --dry-run        show files/commands without writing
                  --local-only     prepare files without GitHub operations
                  --verbose        print each command before running it

                run:
                  With no project argument, compiles <project-dir>/program.manifest to a
                  native executable. With a GitHub project, fetches rio.xml, resolves
                  dependencies from GitHub, builds and starts it. Java is the default
                  runtime; static|dynamic require -platform arm|x86.

                split:
                  Extracts a module into a new GitHub project. Copies sources and plugins,
                  vendors the module dependency closure, generates kava.xml, removes the
                  module from the source project, creates/pushes the repo and logs every
                  git/GitHub operation.
                 """.formatted(VERSION);
    }

    /**
     * {@code mini-mvn dist <project-dir> [--to DIR] [--verbose]}: compile a manifest-based
     * program and stage a relocatable distribution (binary + resources) to a directory.
     */
    private static int distProgram(String[] args) throws IOException {
        String dirArg = null;
        Path to = null;
        boolean verbose = false;
        String runtimeOverride = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--verbose") || args[i].equals("-v")) {
                verbose = true;
                continue;
            } else if (args[i].equals("--to") && i + 1 < args.length) {
                to = Path.of(args[i + 1]);
                i++;
                continue;
            } else if (args[i].equals("--runtime") && i + 1 < args.length) {
                runtimeOverride = args[i + 1];
                i++;
                continue;
            } else if (!args[i].startsWith("-") && dirArg == null) {
                dirArg = args[i];
                continue;
            } else {
                throw new UsageException("dist takes a single project directory; unexpected '"
                        + args[i] + "'");
            }
        }

        Path programDir = Path.of(dirArg == null ? "." : dirArg).toAbsolutePath().normalize();
        Path manifest = programDir.resolve("program.manifest");
        if (!Files.isRegularFile(manifest)) {
            throw new UsageException("no program.manifest in " + programDir
                    + "\n  usage: mini-mvn dist <project-dir> [--to DIR] [--verbose]");
        }

        Map<String, String> keys = readManifestKeys(manifest);
        String effectiveRuntime = runtimeOverride;
        if (effectiveRuntime == null) {
            effectiveRuntime = keys.getOrDefault("runtime", "static");
        }
        if (!effectiveRuntime.equals("static") && !effectiveRuntime.equals("dynamic")) {
            throw new UsageException("invalid runtime: " + effectiveRuntime + " (expected static|dynamic)");
        }

        Path root = findKavaXml(programDir);
        if (root == null) {
            throw new UsageException("cannot find kava.xml above " + programDir
                    + "\n  run from inside the mini-compiler repository");
        }
        Path repoDir = root.getParent();
        KavaFile project = KavaFile.load(root);
        Overview overview = loadAll(project, repoDir);
        System.out.println(ENGINE + " dist " + display(repoDir, programDir));
        Engine engine = new Engine(repoDir, project, overview.modules, overview.plugins,
                null, true, null, verbose, 1);
        engine.build();

        Path compilerJar = repoDir.resolve("target/compiler/mini-compiler-all.jar");
        if (!Files.isRegularFile(compilerJar)) {
            throw new BuildException("compiler jar not found at " + compilerJar
                    + " (build failed?)");
        }
        List<String> compile = new ArrayList<>();
        compile.add(javaHome() + "/bin/java");
        compile.add("-jar");
        compile.add(compilerJar.toString());
        compile.add("--manifest");
        compile.add(manifest.toString());
        compile.add("--emit=exe");
        compile.add("--runtime");
        compile.add(effectiveRuntime);
        Path runtimeArchive = runtimeArchive(repoDir);
        if (Files.isRegularFile(runtimeArchive)) {
            compile.add("--runtime-lib");
            compile.add(runtimeArchive.getParent().toString());
        }
        Map<String, String> env = new HashMap<>();
        env.put("MINI_CORELIB", repoDir.resolve("corelib").toString());
        mini.maven.engine.Exec.Result r = mini.maven.engine.Exec.run(compile, programDir, env, verbose);
        if (!r.ok()) {
            throw new BuildException("compiling " + display(repoDir, programDir)
                    + " failed:\n" + r.output());
        }

        String mainClass = keys.get("main");
        if (mainClass == null) {
            throw new UsageException(manifest + ": no 'main:' key, cannot locate the binary");
        }
        String outDir = keys.getOrDefault("out", "out");
        String simpleName = mainClass.contains(".")
                ? mainClass.substring(mainClass.lastIndexOf('.') + 1) : mainClass;
        Path binary = programDir.resolve(outDir).resolve(simpleName);
        if (!Files.isRegularFile(binary)) {
            throw new BuildException("expected a binary at " + binary + " but none was produced");
        }

        Path distDir = (to == null ? programDir.resolve("dist") : to).toAbsolutePath().normalize();
        cleanDir(distDir);
        Files.createDirectories(distDir);
        Path distBinary = distDir.resolve(binary.getFileName());
        Files.copy(binary, distBinary,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        int copied = 1;

        // Copy common resource files (certs, configs, html, etc.)
        for (String name : new String[] {"cert.pem", "key.pem", "ca.pem", "server.pem",
                "server.key", "config.json", "settings.json", "README.md", "run.sh", "start.sh"}) {
            Path src = programDir.resolve(name);
            if (Files.isRegularFile(src)) {
                Files.copy(src, distDir.resolve(name),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            }
        }
        // Also copy any *.pem files in project dir
        try (var stream = Files.list(programDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String fn = p.getFileName().toString();
                if (fn.endsWith(".pem") && Files.isRegularFile(p)) {
                    Path dest = distDir.resolve(fn);
                    if (!Files.exists(dest)) {
                        Files.copy(p, dest,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                        copied++;
                    }
                }
            }
        }

        // Bundle libraries
        try {
            if (effectiveRuntime.equals("dynamic")) {
                // With dynamic mini runtime we bundle OpenSSL for portability
                String[] libs = {"libssl.so.3", "libcrypto.so.3", "libssl.so", "libcrypto.so"};
                for (String lib : libs) {
                    Path src = findSystemLib(lib);
                    if (src != null && Files.isRegularFile(src)) {
                        Path dest = distDir.resolve(src.getFileName());
                        if (!Files.exists(dest)) {
                            Files.copy(src, dest,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                            copied++;
                        }
                    }
                }
                // Bundle mini runtime
                for (String lib : new String[] {"libkava.so", "libmini_runtime.so"}) {
                    Path src = repoDir.resolve("target").resolve(targetTriple(repoDir)).resolve(lib);
                    if (!Files.exists(src)) {
                        src = repoDir.resolve("target").resolve("x86_64-linux-gnu").resolve(lib);
                    }
                    if (!Files.exists(src)) {
                        src = repoDir.resolve("target").resolve("aarch64-linux-gnu").resolve(lib);
                    }
                    if (src != null && Files.exists(src)) {
                        Path dest = distDir.resolve(src.getFileName());
                        if (!Files.exists(dest)) {
                            Files.copy(src, dest,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                            copied++;
                        }
                        break;
                    }
                }
            } else {
                // static mini runtime - do not bundle libkava or OpenSSL by default
            }
            // Try to patch rpath with patchelf if available
            try {
                ProcessBuilder pb = new ProcessBuilder("patchelf", "--set-rpath", "$ORIGIN", distBinary.toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getInputStream().readAllBytes();
                p.waitFor();
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }

        Path runSh = distDir.resolve("run.sh");
        String runScript = "#!/bin/sh\ncd \"$(dirname \"$0\")\"\nexport LD_LIBRARY_PATH=\".:$LD_LIBRARY_PATH\"\n./" + binary.getFileName() + " \"$@\"\n";
        Files.writeString(runSh, runScript);
        try { runSh.toFile().setExecutable(true, false); } catch (Exception ignored) {}
        copied++;

        System.out.println(ENGINE + ": packaged " + copied + " file(s) to "
                + display(repoDir, distDir));
        return 0;
    }

    private record Overview(java.util.Map<String, ModuleFile> modules,
                            java.util.Map<String, PluginFile> plugins) {
    }

    private record SuiteHit(ModuleFile module, ModuleFile.Suite suite) {
    }

    private record RioProject(Path dir, RioFile descriptor, String repository) {
    }

    static final class RunOptions {
        String spec;
        String file;
        String platform;
        String profile;
        String port;
        String workspace;
        String ref;
        boolean verbose;
        final List<String> programArgs = new ArrayList<>();

        static RunOptions parse(String[] args) {
            RunOptions options = new RunOptions();
            int separator = -1;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--")) {
                    separator = i;
                    break;
                }
            }
            int limit = separator < 0 ? args.length : separator;
            for (int i = 1; i < limit; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--file", "-f" -> options.file = Options.value(args, ++i, arg);
                    case "--platform", "-platform" -> options.platform = Options.value(args, ++i, arg);
                    case "--profile", "-profile" -> options.profile = Options.value(args, ++i, arg);
                    case "--port" -> options.port = Options.value(args, ++i, arg);
                    case "--workspace" -> options.workspace = Options.value(args, ++i, arg);
                    case "--ref" -> options.ref = Options.value(args, ++i, arg);
                    case "--verbose", "-v" -> options.verbose = true;
                    default -> {
                        if (arg.startsWith("-")) throw new UsageException("unknown option: " + arg);
                        if (options.spec != null) {
                            throw new UsageException("run takes a single project: " + arg);
                        }
                        options.spec = arg;
                    }
                }
            }
            if (separator >= 0) {
                for (int i = separator + 1; i < args.length; i++) {
                    options.programArgs.add(args[i]);
                }
            }
            return options;
        }
    }

    static final class SplitOptions {
        Path file = Path.of("kava.xml");
        Path to;
        String module;
        String repo;
        String visibility = "public";
        String group;
        boolean force;
        boolean dryRun;
        boolean localOnly;
        boolean verbose;

        static SplitOptions parse(String[] args) {
            SplitOptions options = new SplitOptions();
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--file", "-f" -> options.file = Path.of(Options.value(args, ++i, arg));
                    case "--to" -> options.to = Path.of(Options.value(args, ++i, arg));
                    case "--module", "-m" -> options.module = Options.value(args, ++i, arg);
                    case "--repo" -> options.repo = Options.value(args, ++i, arg);
                    case "--visibility" -> options.visibility = Options.value(args, ++i, arg);
                    case "--group" -> options.group = Options.value(args, ++i, arg);
                    case "--force" -> options.force = true;
                    case "--dry-run" -> options.dryRun = true;
                    case "--local-only" -> options.localOnly = true;
                    case "--verbose", "-v" -> options.verbose = true;
                    default -> throw new UsageException("unknown option: " + arg);
                }
            }
            if (options.module == null) throw new UsageException("split requires --module");
            if (options.repo == null) throw new UsageException("split requires --repo");
            return options;
        }
    }

    static final class Options {
        Path file;
        Path to;
        String module;
        String target;
        String suite;
        boolean withDeps;
        boolean verbose;
        int jobs = 1;

        static Options parse(String[] args, int from) {
            Options options = new Options();
            for (int i = from; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--file", "-f" -> options.file = Path.of(Options.value(args, ++i, arg));
                    case "--to" -> options.to = Path.of(Options.value(args, ++i, arg));
                    case "--module", "-m" -> options.module = Options.value(args, ++i, arg);
                    case "--target", "-t" -> options.target = Options.value(args, ++i, arg);
                    case "--suite", "-s" -> options.suite = Options.value(args, ++i, arg);
                    case "--with-deps" -> options.withDeps = true;
                    case "--verbose", "-v" -> options.verbose = true;
                    case "--jobs", "-j" -> options.jobs = number(Options.value(args, ++i, arg));
                    default -> throw new UsageException("unknown option: " + arg);
                }
            }
            if (options.jobs < 1) throw new UsageException("--jobs must be at least 1");
            return options;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) throw new UsageException(option + " requires a value");
            return args[index];
        }

        private static int number(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new UsageException("expected a number, got '" + value + "'");
            }
        }
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
