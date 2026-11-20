package mini.maven.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import mini.maven.model.KavaFile;
import mini.maven.model.ModuleFile;
import mini.maven.model.ModuleFile.Artifact;
import mini.maven.model.ModuleFile.Dependency;
import mini.maven.model.ModuleFile.SourceSet;
import mini.maven.model.PluginFile;
import mini.maven.model.StateFile.FileState;

public final class Engine {

    private final Path root;
    private final KavaFile project;
    private final Map<String, ModuleFile> modules;
    private final Map<String, PluginFile> plugins;
    private final String onlyModule;
    private final boolean withDeps;
    private final String onlyTarget;
    private final boolean verbose;
    private final int jobs;
    private final Map<String, String> versions = new HashMap<>();
    /** Per-compiler cache of whether OpenSSL dev headers are usable (for TLS in mini_tls.c). */
    private final Map<String, Boolean> tlsCache = new HashMap<>();
    private int built;
    private int upToDate;
    private int skipped;

    public Engine(Path root, KavaFile project, Map<String, ModuleFile> modules,
                  Map<String, PluginFile> plugins, String onlyModule, boolean withDeps,
                  String onlyTarget, boolean verbose, int jobs) {
        this.root = root.toAbsolutePath().normalize();
        this.project = project;
        this.modules = modules;
        this.plugins = plugins;
        this.onlyModule = onlyModule;
        this.withDeps = withDeps;
        this.onlyTarget = onlyTarget;
        this.verbose = verbose;
        this.jobs = jobs;
    }

    public void build() {
        if (onlyTarget != null) {
            boolean known = project.platforms().stream().anyMatch(p -> p.id().equals(onlyTarget));
            if (!known) throw new BuildException("unknown platform: " + onlyTarget);
        }
        Set<String> selected = selectedModules();
        List<String> order = order(selected);
        System.out.println("mini-mvn: building " + String.join(", ", order));
        try (StateStore state = new StateStore(root, "mini-mvn")) {
            for (String name : order) {
                ModuleFile module = modules.get(name);
                buildModule(module, state);
            }
        }
        System.out.println("mini-mvn: built " + built + ", up-to-date " + upToDate
                + ", skipped " + skipped);
    }

    private Set<String> selectedModules() {
        if (onlyModule == null) return new LinkedHashSet<>(project.modules().stream()
                .map(KavaFile.ModuleRef::name).toList());
        if (!modules.containsKey(onlyModule)) {
            throw new BuildException("unknown module: " + onlyModule);
        }
        Set<String> selected = new LinkedHashSet<>();
        collect(onlyModule, selected, withDeps);
        return selected;
    }

    private void collect(String name, Set<String> selected, boolean deps) {
        if (!selected.add(name)) return;
        if (!deps) return;
        ModuleFile module = modules.get(name);
        for (Dependency dependency : module.dependencies()) {
            if (ordersBefore(module, dependency) && modules.containsKey(dependency.module())) {
                collect(dependency.module(), selected, true);
            }
        }
    }

    /**
     * Whether {@code dependent} must be built after {@code dependency}. Build- and
     * compile-scoped module dependencies always order first (their output is a build
     * input). A {@code runtime}-scoped module dependency normally does not — that would
     * cycle, since the compiler depends on the corelib at runtime while the corelib
     * depends on the compiler to build — except when the dependent produces a native
     * library ({@code archive}/{@code shared}) that links the dependency's symbols, as
     * the corelib's {@code .so} links {@code libkava}.
     */
    private boolean ordersBefore(ModuleFile dependent, Dependency dependency) {
        if (!dependency.isModule()) return false;
        if (!"runtime".equals(dependency.scope())) return true;
        return producesNativeLibrary(dependent);
    }

    private static boolean producesNativeLibrary(ModuleFile module) {
        for (Artifact artifact : module.artifacts()) {
            if (artifact.type().equals("archive") || artifact.type().equals("shared")) {
                return true;
            }
        }
        return false;
    }

    private List<String> order(Set<String> selected) {
        List<String> order = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String name : selected) visit(name, selected, visiting, done, order);
        return order;
    }

    private void visit(String name, Set<String> selected, Set<String> visiting,
                       Set<String> done, List<String> order) {
        if (done.contains(name)) return;
        if (!visiting.add(name)) throw new BuildException("module dependency cycle at " + name);
        ModuleFile module = modules.get(name);
        if (module == null) throw new BuildException("unknown module: " + name);
        for (Dependency dependency : module.dependencies()) {
            if (ordersBefore(module, dependency) && selected.contains(dependency.module())) {
                visit(dependency.module(), selected, visiting, done, order);
            }
        }
        visiting.remove(name);
        done.add(name);
        order.add(name);
    }

    private void buildModule(ModuleFile module, StateStore state) {
        List<Path> javaSources = collectSources(module.sources("java"));
        List<Path> testSources = collectSources(module.testSources("java"));
        List<Path> cSources = collectSources(module.sources("c"));
        boolean hasJava = !javaSources.isEmpty() || !testSources.isEmpty();

        if (hasJava) {
            String pluginId = javaPlugin(module.name());
            PluginFile plugin = plugins.get(pluginId);
            if (plugin == null) {
                throw new BuildException("module " + module.name()
                        + " binds unknown source plugin '" + pluginId + "'");
            }
            if (provides(plugin, "java-compile") && !"javac".equals(pluginId)) {
                buildKavac(module, plugin, javaSources, state);
            } else {
                buildJava(module, plugin, javaSources, testSources, state);
            }
        } else if (!cSources.isEmpty()) {
            buildC(module, cSources, state);
        } else {
            skipped++;
        }
    }

    /** True when a plugin's comma-separated {@code provides} lists the given capability. */
    private static boolean provides(PluginFile plugin, String capability) {
        for (String token : plugin.provides().split(",")) {
            if (token.trim().equals(capability)) return true;
        }
        return false;
    }

    private void buildJava(ModuleFile module, PluginFile plugin, List<Path> sources,
                           List<Path> testSources, StateStore state) {
        Path target = root.resolve("target").resolve(module.name());
        Path classes = target.resolve("classes");
        Path testClasses = target.resolve("test-classes");

        List<Path> mainClasspath = classpath(module, false, classes);
        if (!sources.isEmpty()) {
            compile(module, plugin, sources, classes, mainClasspath, false, state);
        }

        List<Path> testClasspath = classpath(module, true, classes);
        if (!testSources.isEmpty()) {
            compile(module, plugin, testSources, testClasses, testClasspath, true, state);
        }

        PluginFile jarPlugin = plugins.get(artifactPlugin("jar"));
        if (jarPlugin == null) {
            throw new BuildException("no plugin provides 'jar' artifacts");
        }
        for (Artifact artifact : module.artifacts()) {
            if (artifact.tests()) continue;
            boolean uber = artifact.type().equals("uberjar");
            if (!artifact.type().equals("jar") && !uber) continue;
            if (!Files.isDirectory(classes)) {
                throw new BuildException("module " + module.name() + " has no classes to jar");
            }
            if (uber) {
                uberjar(module, jarPlugin, artifact, classes, target, state);
            } else {
                jar(module, jarPlugin, artifact, classes, target, state);
            }
        }
    }

    private void compile(ModuleFile module, PluginFile plugin, List<Path> sources, Path out,
                         List<Path> classpath, boolean test, StateStore state) {
        String nodeId = test ? "javac:test" : "javac:main";
        String toolPath = toolPath(plugin, "javac");
        String version = version(plugin, "javac", toolPath);
        List<String> settings = settings(plugin);

        List<Path> inputs = new ArrayList<>(sources);
        inputs.add(module.file());
        inputs.add(plugin.file());
        inputs.add(project.file());
        inputs.addAll(classpath);
        List<FileState> inputStates = inputStates(state, inputs);

        List<String> extras = new ArrayList<>();
        extras.add("javac");
        extras.add(version);
        extras.addAll(settings);
        extras.add("out=" + state.pathKey(out));
        extras.add("test=" + test);
        String key = Hashing.key(inputStates, extras);

        List<Path> existing = Files.isDirectory(out) ? files(out) : List.of();
        if (state.fresh(module.name(), nodeId, key, existing)) {
            upToDate++;
            return;
        }

        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new BuildException("cannot create " + out, e);
        }
        String release = plugin.settings().getOrDefault("release", "17");
        String encoding = plugin.settings().getOrDefault("encoding", "UTF-8");
        List<String> args = new ArrayList<>();
        args.add("--release");
        args.add(release);
        args.add("-encoding");
        args.add(encoding);
        args.add("-g");
        args.add("-d");
        args.add(out.toString());
        if (!classpath.isEmpty()) {
            args.add("-cp");
            args.add(join(classpath));
        }
        args.addAll(plugin.args());
        for (Path source : sources) args.add(source.toString());

        Path argFile = root.resolve("target").resolve(".mini-maven").resolve("args")
                .resolve(module.name() + "-" + (test ? "test" : "main") + ".args");
        writeArgs(argFile, args);
        Exec.Result result = exec(List.of(toolPath, "@" + argFile), "javac " + module.name());
        if (!result.ok()) {
            throw new BuildException("javac failed for module " + module.name()
                    + " (" + (test ? "test" : "main") + ")\n" + result.output());
        }

        List<Path> outputs = files(out);
        state.record(module.name(), nodeId, key, "javac " + version, inputs, outputs);
        built++;
        System.out.println("mini-mvn: compiled " + module.name()
                + (test ? " tests" : "") + " (" + sources.size() + " sources)");
    }

    private void jar(ModuleFile module, PluginFile plugin, Artifact artifact, Path classes,
                     Path target, StateStore state) {
        Path out = target.resolve(artifact.id() + ".jar");
        String toolPath = toolPath(plugin, "jar");
        String version = version(plugin, "jar", toolPath);
        List<Path> inputs = new ArrayList<>(files(classes));
        inputs.add(module.file());
        inputs.add(plugin.file());
        inputs.add(project.file());
        List<FileState> inputStates = inputStates(state, inputs);
        List<String> extras = List.of("jar", version, artifact.id(),
                String.valueOf(artifact.mainClass()));
        String key = Hashing.key(inputStates, extras);

        List<Path> existing = Files.isRegularFile(out) ? List.of(out) : List.of();
        if (state.fresh(module.name(), "jar:" + artifact.id(), key, existing)) {
            upToDate++;
            return;
        }

        List<String> command = new ArrayList<>(List.of(toolPath, "--create", "--file",
                out.toString()));
        if (artifact.mainClass() != null) {
            command.add("--main-class");
            command.add(artifact.mainClass());
        }
        command.add("-C");
        command.add(classes.toString());
        command.add(".");
        Exec.Result result = exec(command, "jar " + artifact.id());
        if (!result.ok()) {
            throw new BuildException("jar failed for " + artifact.id() + "\n" + result.output());
        }
        state.record(module.name(), "jar:" + artifact.id(), key, "jar " + version, inputs,
                List.of(out));
        built++;
        System.out.println("mini-mvn: packaged " + root.relativize(out));
    }

    /**
     * Build a self-contained ("fat"/"uber") executable jar: the module's own classes plus
     * every compile-scoped dependency jar, exploded into one staging tree and packaged with
     * a {@code Main-Class}. The result runs with a plain {@code java -jar}, no {@code -cp}.
     *
     * <p>Dependency jars are unzipped in order; the module's own classes win last. A few
     * entry classes are handled specially so the merge stays valid: per-jar
     * {@code META-INF/MANIFEST.MF}, signature files ({@code META-INF/*.SF|*.RSA|*.DSA|*.EC})
     * and {@code module-info.class} are dropped (a merged classpath jar is unsigned and
     * unmodular), while {@code META-INF/services/*} provider lists are concatenated so
     * {@code ServiceLoader} still sees every provider.
     */
    private void uberjar(ModuleFile module, PluginFile plugin, Artifact artifact, Path classes,
                         Path target, StateStore state) {
        Path out = target.resolve(artifact.id() + ".jar");
        String toolPath = toolPath(plugin, "jar");
        String version = version(plugin, "jar", toolPath);

        List<Path> depJars = compileJars(module);

        List<Path> inputs = new ArrayList<>(files(classes));
        inputs.addAll(depJars);
        inputs.add(module.file());
        inputs.add(plugin.file());
        inputs.add(project.file());
        List<FileState> inputStates = inputStates(state, inputs);
        List<String> extras = new ArrayList<>(List.of("uberjar", version, artifact.id(),
                String.valueOf(artifact.mainClass())));
        for (Path jar : depJars) extras.add(state.pathKey(jar));
        String key = Hashing.key(inputStates, extras);

        List<Path> existing = Files.isRegularFile(out) ? List.of(out) : List.of();
        if (state.fresh(module.name(), "uberjar:" + artifact.id(), key, existing)) {
            upToDate++;
            return;
        }

        Path stage = target.resolve("uber").resolve(artifact.id());
        explodeForUber(stage, depJars, classes);

        List<String> command = new ArrayList<>(List.of(toolPath, "--create", "--file",
                out.toString()));
        if (artifact.mainClass() != null) {
            command.add("--main-class");
            command.add(artifact.mainClass());
        }
        command.add("-C");
        command.add(stage.toString());
        command.add(".");
        Exec.Result result = exec(command, "uberjar " + artifact.id());
        if (!result.ok()) {
            throw new BuildException("uberjar failed for " + artifact.id() + "\n"
                    + result.output());
        }
        state.record(module.name(), "uberjar:" + artifact.id(), key, "uberjar " + version,
                inputs, List.of(out));
        built++;
        System.out.println("mini-mvn: packaged " + root.relativize(out)
                + " (uber, " + depJars.size() + " deps)");
    }

    /** The compile-scoped dependency jars to fold into an uberjar: vendored third-party jars
     * and sibling module jars, in declaration order. Missing module jars are an error (build
     * them first); missing vendored jars are an error too, matching {@link #classpath}. */
    private List<Path> compileJars(ModuleFile module) {
        List<Path> jars = new ArrayList<>();
        for (Dependency dependency : module.dependencies()) {
            if (!dependency.scope().equals("compile")) continue;
            if (dependency.isModule()) {
                if (dependency.module().equals(module.name())) continue;
                Path jar = moduleJar(dependency.module());
                if (jar == null || !Files.isRegularFile(jar)) {
                    throw new BuildException("missing artifact for module '"
                            + dependency.module() + "' (build it first)");
                }
                jars.add(jar);
            } else {
                Path jar = vendored(dependency);
                if (!Files.isRegularFile(jar)) {
                    throw new BuildException("missing vendored jar " + dependency.coordinates()
                            + " at " + root.relativize(jar));
                }
                jars.add(jar);
            }
        }
        return dedupe(jars);
    }

    /** Explode {@code depJars} then the module's own {@code classes} into a clean {@code stage}
     * tree, applying the uberjar merge rules (skip manifests/signatures/module-info, merge
     * {@code META-INF/services}). The module's classes are added last so they take precedence. */
    private void explodeForUber(Path stage, List<Path> depJars, Path classes) {
        try {
            if (Files.exists(stage)) deleteRecursively(stage);
            Files.createDirectories(stage);
        } catch (IOException e) {
            throw new BuildException("cannot prepare uberjar staging dir " + stage, e);
        }
        for (Path jar : depJars) explodeJar(jar, stage);
        copyClasses(classes, stage);
    }

    /** Unzip one jar into {@code stage}, honouring the uberjar merge rules. */
    private void explodeJar(Path jar, Path stage) {
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                Files.newInputStream(jar))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || skipUberEntry(name)) continue;
                Path dest = resolveUnderStage(stage, name);
                Files.createDirectories(dest.getParent());
                if (isServicesEntry(name) && Files.exists(dest)) {
                    appendServices(zip, dest);      // concatenate provider lists across jars
                } else {
                    Files.copy(zip, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new BuildException("cannot explode " + root.relativize(jar)
                    + " into the uberjar: " + e.getMessage(), e);
        }
    }

    /** Copy the module's compiled classes into {@code stage}, overwriting any dependency entry. */
    private void copyClasses(Path classes, Path stage) {
        for (Path file : files(classes)) {
            String name = stage.getFileSystem().getSeparator().equals("/")
                    ? classes.relativize(file).toString()
                    : classes.relativize(file).toString().replace('\\', '/');
            if (skipUberEntry(name)) continue;
            Path dest = resolveUnderStage(stage, name);
            try {
                Files.createDirectories(dest.getParent());
                if (isServicesEntry(name) && Files.exists(dest)) {
                    try (var in = Files.newInputStream(file)) {
                        appendServices(in, dest);
                    }
                } else {
                    Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new BuildException("cannot stage class " + name
                        + " into the uberjar: " + e.getMessage(), e);
            }
        }
    }

    /** Resolve a jar-entry name under {@code stage}, refusing paths that escape it (zip-slip). */
    private static Path resolveUnderStage(Path stage, String name) {
        Path dest = stage.resolve(name).normalize();
        if (!dest.startsWith(stage)) {
            throw new BuildException("refusing unsafe jar entry '" + name + "'");
        }
        return dest;
    }

    /** Entries dropped when merging jars: per-jar manifests, signatures, and module descriptors. */
    private static boolean skipUberEntry(String name) {
        if (name.equals("META-INF/MANIFEST.MF")) return true;
        if (name.equals("module-info.class") || name.endsWith("/module-info.class")) return true;
        if (name.startsWith("META-INF/")) {
            String upper = name.toUpperCase(java.util.Locale.ROOT);
            return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                    || upper.endsWith(".EC");
        }
        return false;
    }

    private static boolean isServicesEntry(String name) {
        return name.startsWith("META-INF/services/");
    }

    /** Append a provider list to an existing one, keeping a newline between the two files. */
    private static void appendServices(java.io.InputStream in, Path dest) throws IOException {
        byte[] existing = Files.readAllBytes(dest);
        try (var out = Files.newOutputStream(dest,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.APPEND)) {
            if (existing.length > 0 && existing[existing.length - 1] != '\n') {
                out.write('\n');
            }
            in.transferTo(out);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) deleteRecursively(entry);
            }
        }
        Files.deleteIfExists(path);
    }

    // ==================================================================
    // Native modules: C via gcc/ar, corelib via the mini-compiler (kavac).
    // Both produce per-platform archives (.a) and shared objects (.so).
    // ==================================================================

    /**
     * The platforms to build native artifacts for. An explicit {@code --target} names one.
     * With no {@code --target}, a platform is built when its toolchain can actually run here:
     * the host arch always qualifies, and a foreign arch only when its cross compiler is on
     * PATH. This keeps the two-arch distribution building on a box with the cross toolchain
     * installed, while a native distro without it (Alpine/Termux) quietly builds just the
     * host arch — a foreign-arch library would otherwise fail the link with "file in wrong
     * format" (ELF EM mismatch) once the host linker sees foreign objects.
     */
    private List<KavaFile.Platform> targetPlatforms() {
        List<KavaFile.Platform> out = new ArrayList<>();
        for (KavaFile.Platform platform : project.platforms()) {
            if (onlyTarget != null) {
                if (onlyTarget.equals(platform.id())) out.add(platform);
            } else if (isHostArch(platform.arch()) || crossToolchainAvailable(platform)) {
                out.add(platform);
            }
        }
        if (out.isEmpty()) {
            throw new BuildException(onlyTarget != null
                    ? "no platform matches target " + onlyTarget
                    : "no buildable platform for host arch " + hostArch()
                      + "; name one with --target");
        }
        return out;
    }

    /**
     * Whether a real cross compiler for a foreign platform is present. It is not enough for
     * a command named {@code <triple>-gcc} to be on PATH: on Alpine/Termux the prefixed name
     * is often a wrapper around the native (host-arch) gcc, which would compile the foreign
     * objects as host-arch and then fail the link with "file in wrong format". So ask the
     * compiler what it actually targets ({@code gcc -dumpmachine}) and accept it only when
     * that machine's arch matches the platform's.
     */
    private boolean crossToolchainAvailable(KavaFile.Platform platform) {
        PluginFile gcc = plugins.get("gcc");
        if (gcc == null || gcc.tool("cc") == null) return false;
        String cc = gcc.tool("cc").path().replace("${platform}", platform.id());
        if (cc.contains("${") || !onPath(cc)) return false;
        try {
            Exec.Result r = Exec.run(List.of(cc, "-dumpmachine"), root, null, false);
            if (!r.ok()) return false;
            String machineArch = r.output().strip().toLowerCase(java.util.Locale.ROOT);
            // -dumpmachine prints e.g. "x86_64-linux-gnu" or "aarch64-alpine-linux-musl".
            String want = platform.arch().toLowerCase(java.util.Locale.ROOT);
            String wantAlt = switch (want) {
                case "x86_64", "amd64" -> "x86_64";
                case "aarch64", "arm64" -> "aarch64";
                default -> want;
            };
            return machineArch.startsWith(wantAlt + "-") || machineArch.startsWith(want + "-");
        } catch (IOException e) {
            return false;
        }
    }

    /** The build host's CPU arch, normalised ({@code x86_64}, {@code aarch64}). */
    private static String hostArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    /** Whether a platform's declared arch is the host's (so its native tools run here). */
    private static boolean isHostArch(String platformArch) {
        String a = platformArch == null ? "" : platformArch.toLowerCase(java.util.Locale.ROOT);
        String host = hostArch();
        return switch (a) {
            case "amd64", "x86_64" -> host.equals("x86_64");
            case "aarch64", "arm64" -> host.equals("aarch64");
            default -> a.equals(host);
        };
    }

    /** A C module ({@code runtime-native}): compile each source to a PIC {@code .o}, then
     * archive and/or link a shared object per the module's artifacts, once per platform. */
    private void buildC(ModuleFile module, List<Path> sources, StateStore state) {
        PluginFile gcc = requirePlugin("c-compile", "c source compilation");
        for (KavaFile.Platform platform : targetPlatforms()) {
            List<Path> objects = compileC(module, gcc, sources, platform, state);
            emitNativeArtifacts(module, gcc, objects, platform, state);
        }
    }

    /** The corelib ({@code kavac}): run the mini-compiler over the Java sources to emit a
     * PIC object, then archive/link it the same way a C module is, once per platform. */
    private void buildKavac(ModuleFile module, PluginFile kavac, List<Path> sources,
                            StateStore state) {
        PluginFile gcc = requirePlugin("c-compile", "shared-object linking");
        for (KavaFile.Platform platform : targetPlatforms()) {
            List<Path> objects = compileKavac(module, kavac, sources, platform, state);
            emitNativeArtifacts(module, gcc, objects, platform, state);
        }
    }

    /**
     * Whether the given C compiler can build against OpenSSL — decided by actually
     * test-compiling {@code #include <openssl/ssl.h>} with it, because a header probe alone
     * is unreliable (a cross compiler may see a host {@code ssl.h} yet lack the arch-specific
     * {@code opensslconf.h}). Memoized per compiler path.
     */
    private boolean tlsAvailable(String cc) {
        return tlsCache.computeIfAbsent(cc, c -> {
            try {
                Path probe = Files.createTempFile("mini-tls-probe", ".c");
                Files.writeString(probe, "#include <openssl/ssl.h>\nint main(void){return 0;}\n");
                Path obj = Files.createTempFile("mini-tls-probe", ".o");
                try {
                    Exec.Result r = Exec.run(
                            List.of(c, "-fsyntax-only", probe.toString()), root, null, false);
                    return r.ok();
                } finally {
                    Files.deleteIfExists(probe);
                    Files.deleteIfExists(obj);
                }
            } catch (IOException e) {
                return false;
            }
        });
    }

    /** Compile C sources to {@code target/<platform>/obj/<module>/*.o} with {@code -fPIC}. */
    private List<Path> compileC(ModuleFile module, PluginFile gcc, List<Path> sources,
                                KavaFile.Platform platform, StateStore state) {
        String cc = toolPathFor(gcc, "cc", platform);
        String version = version(gcc, "cc", cc);
        Path objDir = objDir(module, platform);
        List<Path> objects = new ArrayList<>();

        List<String> baseArgs = gccBaseArgs(gcc);
        for (Path include : includeDirs(module)) {
            baseArgs.add("-I" + include);
        }
        for (ModuleFile.Define define : module.defines()) {
            baseArgs.add(define.value().equals("1")
                    ? "-D" + define.name() : "-D" + define.name() + "=" + define.value());
        }
        // TLS (mini_tls.c) needs this platform compiler's OpenSSL dev headers. When they are
        // absent (e.g. a cross aarch64 toolchain with no aarch64 OpenSSL), compile the TLS
        // natives as clean-failing stubs so the runtime still builds and links.
        if (!tlsAvailable(cc)) baseArgs.add("-DMINI_NO_TLS");

        for (Path source : sources) {
            Path object = objDir.resolve(stem(source) + ".o");
            objects.add(object);
            String nodeId = "cc:" + platform.id() + ":" + state.pathKey(object);

            List<Path> inputs = new ArrayList<>(List.of(source, module.file(), gcc.file(),
                    project.file()));
            inputs.addAll(includeHeaders(module));
            List<FileState> inputStates = inputStates(state, inputs);
            List<String> extras = new ArrayList<>(List.of("cc", version, platform.id()));
            extras.addAll(baseArgs);
            String key = Hashing.key(inputStates, extras);

            if (state.fresh(module.name(), nodeId, key,
                    Files.isRegularFile(object) ? List.of(object) : List.of())) {
                upToDate++;
                continue;
            }
            mkdirs(object.getParent());
            List<String> command = new ArrayList<>(List.of(cc));
            command.addAll(baseArgs);
            command.add("-c");
            command.add(source.toString());
            command.add("-o");
            command.add(object.toString());
            Exec.Result result = exec(command, "cc " + source.getFileName());
            if (!result.ok()) {
                throw new BuildException("gcc failed for " + root.relativize(source)
                        + " (" + platform.id() + ")\n" + result.output());
            }
            state.record(module.name(), nodeId, key, "gcc " + version, inputs, List.of(object));
            built++;
            System.out.println("mini-mvn: compiled " + root.relativize(source)
                    + " (" + platform.id() + ")");
        }
        return objects;
    }

    /** Run the mini-compiler ({@code --emit=lib}) over the corelib, producing one PIC
     * object per platform at {@code target/<platform>/obj/<module>/<module>.o}. */
    private List<Path> compileKavac(ModuleFile module, PluginFile kavac, List<Path> sources,
                                    KavaFile.Platform platform, StateStore state) {
        String java = toolPathFor(kavac, "java", platform);
        String version = version(kavac, "java", java);
        Path objDir = objDir(module, platform);
        String nodeId = "kavac:" + platform.id();

        Path compilerJar = moduleArtifactJar("compiler");
        if (compilerJar == null || !Files.isRegularFile(compilerJar)) {
            throw new BuildException("kavac needs compiler's jar; build the compiler first");
        }
        Path corelibDir = corelibSourceRoot(module);
        String compilerTarget = compilerTarget(platform);
        String mainClass = kavac.settings().getOrDefault("mainClass", "mini.MiniCompiler");
        String emit = kavac.settings().getOrDefault("emit", "lib");

        // The mini-compiler runs on its own classpath: its jar plus the vendored JDT jars
        // it needs to parse Java. Build it from the compiler module's compile dependencies.
        List<Path> classpath = new ArrayList<>();
        classpath.add(compilerJar);
        ModuleFile compiler = modules.get("compiler");
        if (compiler != null) {
            for (Dependency dependency : compiler.dependencies()) {
                if (dependency.isModule() || dependency.scope().equals("test")) continue;
                Path jar = vendored(dependency);
                if (Files.isRegularFile(jar)) classpath.add(jar);
            }
        }
        String cp = join(classpath);

        List<Path> inputs = new ArrayList<>(sources);
        inputs.add(module.file());
        inputs.add(kavac.file());
        inputs.add(project.file());
        inputs.add(compilerJar);
        List<FileState> inputStates = inputStates(state, inputs);
        List<String> extras = List.of("kavac", version, platform.id(), compilerTarget,
                emit, mainClass);
        String key = Hashing.key(inputStates, extras);

        List<Path> existing = collectObjects(objDir);
        if (!existing.isEmpty() && state.fresh(module.name(), nodeId, key, existing)) {
            upToDate++;
            return existing;
        }
        mkdirs(objDir);
        List<String> command = new ArrayList<>(List.of(java, "-cp", cp, mainClass));
        command.add("--emit=" + emit);
        command.add("--target");
        command.add(compilerTarget);
        command.add("--corelib");
        command.add(corelibDir.toString());
        command.add("-d");
        command.add(objDir.toString());
        for (Path source : sources) command.add(source.toString());
        Exec.Result result = exec(command, "kavac " + module.name());
        if (!result.ok()) {
            throw new BuildException("kavac failed for module " + module.name()
                    + " (" + platform.id() + ")\n" + result.output());
        }
        // The mini-compiler writes one .o per compilation unit under -d/obj; collect them all.
        List<Path> produced = collectObjects(objDir);
        if (produced.isEmpty()) {
            throw new BuildException("kavac produced no object for " + module.name()
                    + " (" + platform.id() + ")");
        }
        state.record(module.name(), nodeId, key, "kavac " + version, inputs, produced);
        built++;
        System.out.println("mini-mvn: compiled corelib (" + platform.id() + ", "
                + produced.size() + " objects)");
        return produced;
    }

    /** Produce the module's {@code archive} ({@code ar}) and {@code shared} ({@code gcc -shared})
     * artifacts for one platform from the freshly built objects. */
    private void emitNativeArtifacts(ModuleFile module, PluginFile gcc, List<Path> objects,
                                     KavaFile.Platform platform, StateStore state) {
        for (ModuleFile.Artifact artifact : module.artifacts()) {
            switch (artifact.type()) {
                case "archive" -> archive(module, artifact, objects, platform, state);
                case "shared" -> shared(module, gcc, artifact, objects, platform, state);
                default -> { }    // jar/executable are not native artifacts
            }
        }
    }

    /** {@code ar rcs target/<platform>/<id>} over the module's objects. */
    private void archive(ModuleFile module, ModuleFile.Artifact artifact, List<Path> objects,
                         KavaFile.Platform platform, StateStore state) {
        PluginFile ar = requirePlugin("archive", "static archiving");
        String arTool = toolPathFor(ar, "ar", platform);
        String version = version(ar, "ar", arTool);
        Path out = libDir(platform).resolve(artifact.id());
        String nodeId = "ar:" + platform.id() + ":" + artifact.id();

        List<Path> inputs = new ArrayList<>(objects);
        inputs.add(module.file());
        inputs.add(ar.file());
        List<FileState> inputStates = inputStates(state, inputs);
        List<String> extras = List.of("ar", version, platform.id(), artifact.id());
        String key = Hashing.key(inputStates, extras);

        if (state.fresh(module.name(), nodeId, key,
                Files.isRegularFile(out) ? List.of(out) : List.of())) {
            upToDate++;
            return;
        }
        mkdirs(out.getParent());
        try {
            Files.deleteIfExists(out);     // ar appends; start clean for deterministic output
        } catch (IOException e) {
            throw new BuildException("cannot remove stale " + out, e);
        }
        List<String> command = new ArrayList<>(List.of(arTool, "rcs", out.toString()));
        for (Path object : objects) command.add(object.toString());
        Exec.Result result = exec(command, "ar " + artifact.id());
        if (!result.ok()) {
            throw new BuildException("ar failed for " + artifact.id()
                    + " (" + platform.id() + ")\n" + result.output());
        }
        state.record(module.name(), nodeId, key, "ar " + version, inputs, List.of(out));
        built++;
        System.out.println("mini-mvn: archived " + root.relativize(out));
    }

    /** {@code gcc -shared -fPIC} over the module's objects into {@code target/<platform>/<id>},
     * linking corelib against libkava (its {@code runtime}-scoped dependency). */
    private void shared(ModuleFile module, PluginFile gcc, ModuleFile.Artifact artifact,
                        List<Path> objects, KavaFile.Platform platform, StateStore state) {
        String cc = toolPathFor(gcc, "cc", platform);
        String version = version(gcc, "cc", cc);
        Path out = libDir(platform).resolve(artifact.id());
        String nodeId = "so:" + platform.id() + ":" + artifact.id();

        List<String> linkLibs = sharedLibDeps(module, platform);
        List<Path> inputs = new ArrayList<>(objects);
        inputs.add(module.file());
        inputs.add(gcc.file());
        List<FileState> inputStates = inputStates(state, inputs);
        List<String> extras = new ArrayList<>(List.of("so", version, platform.id(),
                artifact.id()));
        extras.addAll(linkLibs);
        String key = Hashing.key(inputStates, extras);

        if (state.fresh(module.name(), nodeId, key,
                Files.isRegularFile(out) ? List.of(out) : List.of())) {
            upToDate++;
            return;
        }
        mkdirs(out.getParent());
        List<String> command = new ArrayList<>(List.of(cc, "-shared", "-fPIC"));
        command.add("-o");
        command.add(out.toString());
        for (Path object : objects) command.add(object.toString());
        command.addAll(linkLibs);
        command.add("-lm");
        // The runtime .so must resolve OpenSSL's SSL_* when TLS was compiled in (mini_tls.c).
        if (tlsAvailable(cc)) {
            command.add("-lssl");
            command.add("-lcrypto");
        }
        Exec.Result result = exec(command, "link " + artifact.id());
        if (!result.ok()) {
            throw new BuildException("gcc -shared failed for " + artifact.id()
                    + " (" + platform.id() + ")\n" + result.output());
        }
        state.record(module.name(), nodeId, key, "gcc -shared " + version, inputs, List.of(out));
        built++;
        System.out.println("mini-mvn: linked " + root.relativize(out));
    }

    /** Link flags for a shared object's module dependencies: {@code -L<dir> -l<name>} for each
     * module dependency that itself produces a shared artifact on this platform. */
    private List<String> sharedLibDeps(ModuleFile module, KavaFile.Platform platform) {
        List<String> flags = new ArrayList<>();
        Path libDir = libDir(platform);
        boolean addedPath = false;
        for (Dependency dependency : module.dependencies()) {
            if (!dependency.isModule()) continue;
            ModuleFile dep = modules.get(dependency.module());
            if (dep == null) continue;
            for (ModuleFile.Artifact artifact : dep.artifacts()) {
                if (!artifact.type().equals("shared")) continue;
                String libName = linkerName(artifact.id());
                if (libName == null) continue;
                if (!addedPath) {
                    flags.add("-L" + libDir);
                    flags.add("-Wl,-rpath,$ORIGIN");
                    addedPath = true;
                }
                flags.add("-l" + libName);
            }
        }
        return flags;
    }

    /** {@code libkava.so} -> {@code kava}; anything not matching {@code lib*.so} is skipped. */
    private static String linkerName(String artifactId) {
        if (artifactId.startsWith("lib") && artifactId.endsWith(".so")) {
            return artifactId.substring(3, artifactId.length() - 3);
        }
        return null;
    }

    // ---- native build helpers ----------------------------------------

    private PluginFile requirePlugin(String capability, String what) {
        for (PluginFile plugin : plugins.values()) {
            if (provides(plugin, capability)) return plugin;
        }
        throw new BuildException("no plugin provides '" + capability + "' (needed for "
                + what + ")");
    }

    private List<String> gccBaseArgs(PluginFile gcc) {
        List<String> args = new ArrayList<>();
        Map<String, String> s = gcc.settings();
        if (Boolean.parseBoolean(s.getOrDefault("pic", "true"))) args.add("-fPIC");
        if (Boolean.parseBoolean(s.getOrDefault("debug", "false"))) args.add("-g");
        String optimize = s.get("optimize");
        if (optimize != null && !optimize.isBlank()) args.add("-O" + optimize);
        if (Boolean.parseBoolean(s.getOrDefault("deps", "false"))) {
            args.add("-MMD");
            args.add("-MP");
        }
        args.addAll(gcc.args());
        return args;
    }

    private List<Path> includeDirs(ModuleFile module) {
        List<Path> dirs = new ArrayList<>();
        for (ModuleFile.IncludeDir include : module.includes()) dirs.add(include.dir());
        return dirs;
    }

    private List<Path> includeHeaders(ModuleFile module) {
        List<Path> headers = new ArrayList<>();
        for (Path dir : includeDirs(module)) {
            for (Path p : files(dir)) {
                String n = p.toString();
                if (n.endsWith(".h")) headers.add(p);
            }
        }
        return headers;
    }

    private Path objDir(ModuleFile module, KavaFile.Platform platform) {
        return libDir(platform).resolve("obj").resolve(module.name());
    }

    private Path libDir(KavaFile.Platform platform) {
        return root.resolve("target").resolve(platform.id());
    }

    /** The source root to pass to the mini-compiler as {@code --corelib}. */
    private Path corelibSourceRoot(ModuleFile module) {
        for (ModuleFile.SourceSet set : module.sources("java")) return set.dir();
        throw new BuildException("corelib module has no java source directory");
    }

    private Path moduleArtifactJar(String moduleName) {
        ModuleFile module = modules.get(moduleName);
        if (module == null) return null;
        Path target = root.resolve("target").resolve(moduleName);
        for (ModuleFile.Artifact artifact : module.artifacts()) {
            if (artifact.type().equals("jar") && !artifact.tests()) {
                return target.resolve(artifact.id() + ".jar");
            }
        }
        return null;
    }

    /** Map a platform id to the mini-compiler's {@code --target} name. */
    private static String compilerTarget(KavaFile.Platform platform) {
        return switch (platform.arch()) {
            case "x86_64", "amd64" -> "x86-64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new BuildException("no mini-compiler target for arch "
                    + platform.arch());
        };
    }

    /** Like {@link #toolPath} but also expands {@code ${platform}} to the platform id. */
    private String toolPathFor(PluginFile plugin, String toolName, KavaFile.Platform platform) {
        PluginFile.Tool tool = plugin.tool(toolName);
        if (tool == null) {
            throw new BuildException("plugin " + plugin.name() + " has no tool '" + toolName + "'");
        }
        String expanded = tool.path()
                .replace("${platform}", platform.id())
                .replace("${java.home}", System.getProperty("java.home"))
                .replace("${root}", root.toString());
        if (expanded.contains("${")) {
            throw new BuildException("cannot expand tool path '" + tool.path() + "'");
        }
        // When building for the host arch, a GNU-triple-prefixed native tool like
        // "aarch64-linux-gnu-gcc" often does not exist on a native distro (Alpine/Termux
        // ship the compiler simply as "gcc"). Fall back to the unprefixed command when the
        // prefixed one is not on PATH, so a native build needs no cross-toolchain naming.
        if (isHostArch(platform.arch()) && expanded.contains("-") && !onPath(expanded)) {
            String bare = expanded.substring(expanded.lastIndexOf('-') + 1);
            if (onPath(bare)) return bare;
        }
        return expanded;
    }

    /** Whether a bare command name resolves on PATH (or is an executable absolute path). */
    private static boolean onPath(String command) {
        if (command.contains("/")) return Files.isExecutable(Path.of(command));
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir).resolve(command))) return true;
        }
        return false;
    }

    private void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BuildException("cannot create " + dir, e);
        }
    }

    private static String stem(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Every {@code .o} the mini-compiler wrote under a kavac object directory. */
    private static List<Path> collectObjects(Path objDir) {
        List<Path> objects = new ArrayList<>();
        for (Path p : files(objDir)) {
            if (p.toString().endsWith(".o")) objects.add(p);
        }
        return objects;
    }

    private String javaPlugin(String module) {
        String generic = null;
        for (KavaFile.Binding binding : project.bindings()) {
            if (binding.sources() == null || !binding.sources().equals("java")) continue;
            if (binding.module() != null && binding.module().equals(module)) {
                return binding.plugin();
            }
            if (binding.module() == null && binding.artifact() == null) generic = binding.plugin();
        }
        if (generic != null) return generic;
        throw new BuildException("no 'java' source binding for module " + module);
    }

    private String artifactPlugin(String artifact) {
        for (KavaFile.Binding binding : project.bindings()) {
            if (artifact.equals(binding.artifact()) && binding.module() == null) {
                return binding.plugin();
            }
        }
        throw new BuildException("no artifact binding for '" + artifact + "'");
    }

    private List<Path> classpath(ModuleFile module, boolean test, Path ownClasses) {
        List<Path> classpath = new ArrayList<>();
        for (Dependency dependency : module.dependencies()) {
            String scope = dependency.scope();
            if (!test && !scope.equals("compile")) continue;
            if (test && !(scope.equals("compile") || scope.equals("test")
                    || scope.equals("runtime"))) continue;
            if (dependency.isModule()) {
                if (dependency.module().equals(module.name())) continue;
                Path jar = moduleJar(dependency.module());
                if (jar != null && Files.isRegularFile(jar)) classpath.add(jar);
                else if (scope.equals("compile")) {
                    throw new BuildException("missing artifact for module '"
                            + dependency.module() + "' (build it first)");
                }
            } else {
                Path jar = vendored(dependency);
                if (!Files.isRegularFile(jar)) {
                    throw new BuildException("missing vendored jar " + dependency.coordinates()
                            + " at " + root.relativize(jar));
                }
                classpath.add(jar);
            }
        }
        if (test && Files.isDirectory(ownClasses)) classpath.add(ownClasses);
        return dedupe(classpath);
    }

    private Path moduleJar(String moduleName) {
        ModuleFile module = modules.get(moduleName);
        if (module == null) return null;
        Path target = root.resolve("target").resolve(moduleName);
        for (Artifact artifact : module.artifacts()) {
            if (artifact.type().equals("jar") && !artifact.tests()) {
                return target.resolve(artifact.id() + ".jar");
            }
        }
        return null;
    }

    private Path vendored(Dependency dependency) {
        return root.resolve("third-party").resolve("repository")
                .resolve(dependency.group().replace('.', '/'))
                .resolve(dependency.artifact())
                .resolve(dependency.version())
                .resolve(dependency.artifact() + "-" + dependency.version() + ".jar");
    }

    private String toolPath(PluginFile plugin, String toolName) {
        PluginFile.Tool tool = plugin.tool(toolName);
        if (tool == null) {
            throw new BuildException("plugin " + plugin.name() + " has no tool '" + toolName + "'");
        }
        return expand(tool.path());
    }

    private String expand(String value) {
        String out = value.replace("${java.home}", System.getProperty("java.home"))
                .replace("${root}", root.toString());
        if (out.contains("${")) {
            throw new BuildException("cannot expand tool path '" + value + "'");
        }
        return out;
    }

    private String version(PluginFile plugin, String toolName, String toolPath) {
        String cacheKey = plugin.name() + "/" + toolName + "/" + toolPath;
        String known = versions.get(cacheKey);
        if (known != null) return known;
        PluginFile.Tool tool = plugin.tool(toolName);
        String arg = tool.versionArg();
        if (arg == null) {
            known = "unknown";
        } else {
            List<String> command = new ArrayList<>();
            command.add(toolPath);
            for (String piece : arg.trim().split("\\s+")) {
                if (!piece.isEmpty()) command.add(piece);
            }
            try {
                Exec.Result result = Exec.run(command, root, null, false);
                known = result.output().strip();
                if (known.isEmpty()) known = "exit " + result.code();
            } catch (IOException e) {
                known = "unavailable: " + e.getMessage();
            }
        }
        versions.put(cacheKey, known);
        return known;
    }

    private List<String> settings(PluginFile plugin) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(plugin.settings()).entrySet()) {
            out.add(entry.getKey() + "=" + entry.getValue());
        }
        for (String arg : plugin.args()) out.add("arg=" + arg);
        return out;
    }

    private Exec.Result exec(List<String> command, String what) {
        try {
            return Exec.run(command, root, null, verbose);
        } catch (IOException e) {
            throw new BuildException("cannot run " + what + ": " + e.getMessage(), e);
        }
    }

    private static List<FileState> inputStates(StateStore state, List<Path> inputs) {
        List<Path> expanded = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (var walk = Files.walk(input)) {
                    walk.filter(Files::isRegularFile).forEach(expanded::add);
                } catch (IOException e) {
                    throw new BuildException("cannot list " + input, e);
                }
            } else {
                expanded.add(input);
            }
        }
        List<FileState> states = new ArrayList<>();
        for (Path input : sorted(expanded)) states.add(state.fileState(input));
        return states;
    }

    private static List<Path> collectSources(List<SourceSet> sets) {
        List<Path> sources = new ArrayList<>();
        for (SourceSet set : sets) {
            if (!Files.isDirectory(set.dir())) continue;
            String ext = "." + set.language();     // "java" -> ".java", "c" -> ".c"
            try (var walk = Files.walk(set.dir())) {
                walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(ext))
                        .forEach(sources::add);
            } catch (IOException e) {
                throw new BuildException("cannot list sources under " + set.dir(), e);
            }
        }
        sources.sort(Comparator.comparing(Path::toString));
        return sources;
    }

    private static List<Path> files(Path dir) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) return files;
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            throw new BuildException("cannot list files under " + dir, e);
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static List<Path> sorted(List<Path> paths) {
        List<Path> copy = new ArrayList<>(paths);
        copy.sort(Comparator.comparing(Path::toString));
        return copy;
    }

    private static List<Path> dedupe(List<Path> paths) {
        Map<String, Path> unique = new LinkedHashMap<>();
        for (Path path : paths) unique.putIfAbsent(path.toAbsolutePath().normalize().toString(),
                path);
        return new ArrayList<>(unique.values());
    }

    private static String join(List<Path> paths) {
        return String.join(java.io.File.pathSeparator,
                paths.stream().map(Path::toString).toList());
    }

    private static void writeArgs(Path file, List<String> args) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder out = new StringBuilder();
            for (String arg : args) out.append(arg).append('\n');
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BuildException("cannot write " + file, e);
        }
    }
}
