#!/bin/sh
# Locate a JDK 17 or newer and print its JAVA_HOME on stdout.
# Preference: exactly 17 (the pom targets release 17), then 18/19, then 20+.
# Never modifies anything outside this repository.

javac_major() {
    "$1/bin/javac" -version 2>&1 | sed -n 's/^javac \{0,1\}\([0-9][0-9]*\).*/\1/p'
}

is_ok() {
    [ -x "$1/bin/javac" ] || return 1
    v=`javac_major "$1"`
    [ -n "$v" ] || return 1
    [ "$v" -ge 17 ] 2>/dev/null
}

if [ -n "${JAVA_HOME:-}" ] && is_ok "$JAVA_HOME"; then
    printf '%s\n' "$JAVA_HOME"
    exit 0
fi

roots="/usr/lib/jvm /usr/java /opt/java /opt/jdk /Library/Java/JavaVirtualMachines"

# Preferred patterns first: 17, then 18/19, then 20 and up.
for pattern in 'java-17*' 'openjdk-17*' 'jdk-17*' 'java-1.17*' 'zulu17*' \
               'java-18*' 'java-19*' 'openjdk-1[89]*' 'jdk-1[89]*' 'java-1.1[89]*' \
               'java-2*' 'openjdk-2*' 'jdk-2*' 'java-1.2*' 'zulu2*'; do
    for root in $roots; do
        for d in "$root"/$pattern; do
            [ -d "$d" ] || continue
            if is_ok "$d"; then printf '%s\n' "$d"; exit 0; fi
        done
    done
done

# Fall back to any JDK 17+ sitting in a conventional location.
for root in $roots; do
    [ -d "$root" ] || continue
    for d in "$root"/*; do
        [ -d "$d" ] || continue
        if is_ok "$d"; then printf '%s\n' "$d"; exit 0; fi
    done
done

echo "find-jdk17.sh: no JDK 17 or newer found." >&2
echo "  install one, or export JAVA_HOME to a JDK 17+ installation." >&2
exit 1
