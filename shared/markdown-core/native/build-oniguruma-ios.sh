#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$ROOT_DIR/oniguruma-src/src"
LIBS_DIR="$ROOT_DIR/libs"
VERSION_FILE="$ROOT_DIR/oniguruma-version"
ONIG_VERSION="${ONIG_VERSION:-$(cat "$VERSION_FILE")}"
MIN_IOS_VERSION="${MIN_IOS_VERSION:-15.0}"

ONIG_SOURCES=(
  regparse.c regcomp.c regexec.c regenc.c regerror.c regext.c regsyntax.c regtrav.c regversion.c st.c reggnu.c
  unicode.c unicode_unfold_key.c unicode_fold1_key.c unicode_fold2_key.c unicode_fold3_key.c
  ascii.c utf8.c utf16_be.c utf16_le.c utf32_be.c utf32_le.c
  euc_jp.c euc_jp_prop.c sjis.c sjis_prop.c
  iso8859_1.c iso8859_2.c iso8859_3.c iso8859_4.c iso8859_5.c iso8859_6.c iso8859_7.c iso8859_8.c iso8859_9.c
  iso8859_10.c iso8859_11.c iso8859_13.c iso8859_14.c iso8859_15.c iso8859_16.c
  euc_tw.c euc_kr.c big5.c gb18030.c koi8_r.c cp1251.c onig_init.c
)

ensure_source() {
  if [[ ! -f "$SRC_DIR/oniguruma.h" ]]; then
    mkdir -p "$ROOT_DIR/oniguruma-src"
    curl -fsSL "https://github.com/kkos/oniguruma/archive/refs/tags/v${ONIG_VERSION}.tar.gz" |
      tar xz --strip-components=1 -C "$ROOT_DIR/oniguruma-src"
    cp "$ROOT_DIR/config.h" "$SRC_DIR/config.h"
  fi
}

build_target() {
  local sdk="$1"
  local arch="$2"
  local konan_target="$3"
  local min_flag="$4"

  local sdk_path
  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
  local cc
  cc="$(xcrun --sdk "$sdk" -f clang)"

  local build_dir="$ROOT_DIR/build/$konan_target"
  local obj_dir="$build_dir/obj"
  local out_dir="$LIBS_DIR/$konan_target"
  rm -rf "$build_dir"
  mkdir -p "$obj_dir" "$out_dir"

  for source in "${ONIG_SOURCES[@]}"; do
    local object="$obj_dir/${source%.c}.o"
    "$cc" \
      -arch "$arch" \
      -isysroot "$sdk_path" \
      "$min_flag=$MIN_IOS_VERSION" \
      -O2 \
      -DONIG_STATIC \
      -I"$SRC_DIR" \
      -c "$SRC_DIR/$source" \
      -o "$object"
  done

  ar rcs "$out_dir/libonig.a" "$obj_dir"/*.o
  echo "Built $out_dir/libonig.a"
}

ensure_source
build_target iphoneos arm64 iosArm64 -miphoneos-version-min
build_target iphonesimulator arm64 iosSimulatorArm64 -mios-simulator-version-min
build_target iphonesimulator x86_64 iosX64 -mios-simulator-version-min
