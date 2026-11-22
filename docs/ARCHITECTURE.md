# Architecture

`mini-mvn` has two layers.

## Bootstrap (`mini-mvn`)

The shell launcher resolves the checkout root, locates JDK 17+ through
`scripts/find-jdk17.sh`, compiles every `mini-maven/src/main/java/**/*.java` file into
`target/.mini-maven/bootstrap` when sources changed, and runs `mini.maven.Main` with the
vendored jars on the classpath. It passes the checkout root as `-Dmini.mvn.home` so `javaHome()`
can find the JDK script even when `JAVA_HOME` is not exported by the caller.

## Engine (`mini.mvn.*`)

- `Main` — CLI parsing, command dispatch, JUnit test runner, staging/run/dist/publish/verify helpers.
- `engine.Engine` — module selection/ordering, compile/jar/uberjar, native builds, plugin dispatch.
- `engine.RemoteResolver` — GitHub `rio.xml` fetch, commit pinning and source cache.
- `engine.StateStore` / `engine.Hashing` / `engine.HashManifest` — incremental SHA-256 state and artifact manifests.
- `engine.PomWriter` — local publish staging for JAR/POM artifacts.
- `model.*` — strict StAX parsers for `kava.xml`, `rio.xml`, `module.xml`, `plugin.xml`, generated state.
- `xml.*` — secure, namespace-aware XML helpers (external entities and DOCTYPE rejected).

The engine has no third-party runtime dependencies. JUnit jars are used only to compile and
run the engine's own `XmlTest` and `RioTest` suites.

The test runner derives paths and the classpath from the module whose suite is being run, so
any module with `<testSources>` and `<tests>` can be tested with the same command.
