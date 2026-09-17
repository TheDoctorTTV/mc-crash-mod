#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    echo 'This helper supports Linux x86_64. Set JAVA8_HOME to your Java 8 JDK instead.' >&2
    exit 1
fi
if [[ -e .tools/java8 ]]; then
    echo '.tools/java8 already exists; using the existing installation.'
    exit 0
fi
mkdir -p .tools
temp_dir=$(mktemp -d "$PWD/.tools/java8-download.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT
curl --fail --location --retry 3 \
    https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jdk_x64_linux_hotspot_8u504b01.tar.gz \
    --output "$temp_dir/jdk.tar.gz"
echo "9c70e102f527ac674ac2fe9c7d47b9a04e2d19842ba5ab8e9b33f368bbadfaea  $temp_dir/jdk.tar.gz" | sha256sum --check
mkdir "$temp_dir/jdk"
tar -xzf "$temp_dir/jdk.tar.gz" -C "$temp_dir/jdk" --strip-components=1
mv "$temp_dir/jdk" .tools/java8
echo 'Installed project-local Temurin Java 8. Run ./build.sh to build.'
