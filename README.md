# mini-mvn

A standalone, offline, incremental build engine for `kava.xml` projects. It was extracted
from [mini-compiler](https://github.com/krasgit/mini-compiler); other repositories install it
and run it against their own project descriptors.

No system Maven, no network and no `~/.m2` are needed. The only requirements are a JDK 17+
(or `JAVA_HOME`) and `sh`.

## Quick start

```bash
sh ./mini-mvn --help
sh ./mini-mvn doctor
sh ./mini-mvn build
sh ./mini-mvn test
sh ./mini-mvn install --to dist
```

`make build`, `make test`, `make doctor` and `make install` are shortcuts for the same commands.

On first invocation the launcher compiles the engine from `mini-maven/src/main/java` into
`target/.mini-maven/bootstrap` using the JDK found by `scripts/find-jdk17.sh`. Later runs
reuse that bootstrap until an engine source file changes.

## Using the engine in another repository

Install this repository (clone it anywhere) and call its launcher from the target project:

```bash
git clone https://github.com/krasgit/mini-mvn.git
cd ../my-project
MINI_MVN=/path/to/mini-mvn/mini-mvn sh "$MINI_MVN" build
```

The launcher bootstraps from **this** repository, but `mini.maven.Main` reads `kava.xml`
relative to the current working directory, so build inputs, state and output stay in the
project being built. Use `--file /path/to/kava.xml` to select a descriptor explicitly.

Commands:

```
mini-mvn build [--module M] [--with-deps] [--target P] [--file kava.xml]
mini-mvn install [--to DIR] [--module M] [--with-deps] [--file kava.xml]
mini-mvn test [--suite smoke|full] [--file kava.xml]
mini-mvn run [mini-rio|owner/repo] [-platform default|arm|x86] [-profile debug|release|static|dynamic] [--port N] [--workspace DIR] [-- args...]
mini-mvn run <project-dir> [--verbose] [-- args...]
mini-mvn dist <project-dir> [--to DIR] [--verbose]
mini-mvn dependencies --tree [mini-rio|--file rio.xml]
mini-mvn verify [--baseline A --compare B] [--file kava.xml|rio.xml]
mini-mvn publish [mini-rio|--file descriptor] [--to DIR]
mini-mvn doctor [--file kava.xml]
mini-mvn clean [--file kava.xml]
mini-mvn --help
```

`build`, `install`, `test`, `doctor` and `clean` are generic and descriptor-driven.
`run` and `dist` are compatibility commands used by mini-compiler: they compile a
`program.manifest` program with that repository's compiler jar and native libraries, then
run or package the produced binary.

With a GitHub project, `run` fetches `rio.xml`, pins the commit SHA, caches the source under
`~/.cache/mini-mvn`, resolves dependencies, builds and starts it. Java is the default
runtime; `static|dynamic` require `-platform arm|x86`. The engine itself stays Java-only.

```bash
mini-mvn run mini-rio
mini-mvn run mini-rio -platform arm -profile static
mini-mvn verify --file rio.xml
mini-mvn publish mini-rio --to dist/publish
```

## Descriptors

- `kava.xml` — project: platforms, modules, plugins, bindings, goals.
- `rio.xml` — remote runnable project: GitHub repo, platforms, profiles, dependencies, artifacts.
- `mini-maven/module.xml` — this engine module: source sets, vendored JUnit deps, jar artifact, test suites.
- `plugins/*/plugin.xml` — tool definitions (`javac`, `jar`, `junit`).
- `schema/*.xsd` — XML namespaces for project/module/plugin/state/rio files.
- `third-party/repository/` — vendored JUnit jars with `.sha256` checksums.

Verify the vendored jars:

```bash
cd third-party/repository
find . -name '*.sha256' -exec sh -c 'cd "$(dirname "$1")" && sha256sum -c "$(basename "$1")"' _ {} \;
```

More detail is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
