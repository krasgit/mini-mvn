# Vendored dependencies

`mini-mvn` builds offline. Every jar it needs is checked in under `repository/` in the
usual Maven layout:

```
repository/<groupId with dots as slashes>/<artifactId>/<version>/<artifactId>-<version>.jar
```

Each jar has a `<artifactId>-<version>.jar.sha256` beside it. Verify all of them:

```bash
cd third-party/repository
find . -name '*.sha256' -exec sh -c 'cd "$(dirname "$1")" && sha256sum -c "$(basename "$1")"' _ {} \;
```

## Why these jars

The compiler front end uses the Eclipse JDT DOM parser. The build engine is plain Java
and needs no third-party jars itself. Tests use JUnit 5; `mini-mvn` drives them through
the JUnit Platform launcher.

## Licenses

| Group | Artifacts | License |
|---|---|---|
| `org.eclipse.jdt`, `org.eclipse.platform`, `org.osgi` | JDT core, ECJ, Equinox/Core platform, OSGi APIs | Eclipse Public License 2.0 |
| `net.java.dev.jna` | JNA, JNA Platform | Apache License 2.0 / LGPL 2.1 |
| `org.junit.jupiter`, `org.junit.platform` | JUnit 5 | Eclipse Public License 2.0 |
| `org.opentest4j` | opentest4j | Apache License 2.0 |
| `org.apiguardian` | apiguardian-api | Apache License 2.0 |

Upstream license texts are available from the corresponding Maven Central artifacts;
this repository does not modify the jars.
