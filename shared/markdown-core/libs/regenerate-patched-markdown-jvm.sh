#!/usr/bin/env bash
set -euo pipefail
# Regenerates repo/org/jetbrains/markdown-jvm/<ver>/ from Maven Central + ASM patch.
ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="${1:-0.7.18}"
ASM="${ASM_JAR:-$(find "${HOME}/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm" -name 'asm-9*.jar' 2>/dev/null | sort | tail -1)}"
if [[ -z "${ASM}" || ! -f "${ASM}" ]]; then
  echo "Set ASM_JAR to an org.ow2.asm:asm jar" >&2
  exit 1
fi
WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT
IN_JAR="${WORKDIR}/markdown-jvm-${VERSION}.jar"
curl -fsSL -o "${IN_JAR}" "https://repo1.maven.org/maven2/org/jetbrains/markdown-jvm/${VERSION}/markdown-jvm-${VERSION}.jar"
javac -cp "${ASM}" -d "${WORKDIR}" "${ROOT}/PatchRemoveLast.java"
OUT_DIR="${ROOT}/repo/org/jetbrains/markdown-jvm/${VERSION}"
mkdir -p "${OUT_DIR}"
java -cp "${WORKDIR}:${ASM}" PatchRemoveLast "${IN_JAR}" "${OUT_DIR}/markdown-jvm-${VERSION}.jar"
echo "Wrote ${OUT_DIR}/markdown-jvm-${VERSION}.jar"
