#!/usr/bin/env bash
set -euo pipefail

# перейти в корень репо 
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
cd "$ROOT"

# (опционально) найти JDK 17 на macOS; можно задать JAVA_17_HOME снаружи
JAVA_PROP=""
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  J17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  [[ -n "${J17}" ]] && JAVA_PROP="-Dorg.gradle.java.home=${J17}"
elif [[ -n "${JAVA_17_HOME:-}" ]]; then
  JAVA_PROP="-Dorg.gradle.java.home=${JAVA_17_HOME}"
fi

# номер версии мода (без добавок) — попадёт и в mods.toml, и в имя jar
MV="${MOD_VERSION:-1.0.0}"

./gradlew ${JAVA_PROP} \
 -Pmod_version="${MV}" \
 -Pminecraft_version=1.20.1 \
 -Pminecraft_version_range='[1.20.1,1.20.2)' \
 -Pforge_version=47.4.0 \
 -Pforge_version_range='[47,48)' \
 -Ploader_version_range='[47,48)' \
 -Pmapping_channel=official \
 -Pmapping_version=1.20.1 \
 -Pjava_ver=17 \
 clean build

mkdir -p dist

# имя файла теперь задаётся в build.gradle — используем его же тут
ARTIFACT="build/libs/cartopia_${MV}_mc1.20.1_forge47.jar"

if [[ -f "${ARTIFACT}" ]]; then
  cp -f "${ARTIFACT}" dist/
  echo "✅ 1.20.1 build: dist/$(basename "${ARTIFACT}")"
else
  echo "❌ not found expected jar: ${ARTIFACT}"
  exit 1
fi