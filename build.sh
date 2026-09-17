#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# ForgeGradle 3 / Gradle 4.9 need a legacy JDK, not the system Java 25.
if [[ -n "${JAVA8_HOME:-}" ]]; then
    export JAVA_HOME="$JAVA8_HOME"
elif [[ -x "$PWD/.tools/java8/bin/javac" ]]; then
    export JAVA_HOME="$PWD/.tools/java8"
elif [[ -x /usr/lib/jvm/java-8-openjdk/bin/javac ]]; then
    export JAVA_HOME=/usr/lib/jvm/java-8-openjdk
fi
if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/javac" ]] || ! "$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -q '"1.8\.'; then
    echo 'Set JAVA8_HOME to a Java 8 JDK directory before building.' >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
# Keep build caches inside this workspace.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.gradle/user-home}"
if [[ $# -eq 0 ]]; then set -- build; fi
exec ./gradlew "$@"
