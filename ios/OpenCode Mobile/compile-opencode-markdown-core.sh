#!/bin/sh
set -e

if [ -z "${JAVA_HOME:-}" ]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1 && /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  elif [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  elif [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  fi
fi

cd "$SRCROOT/../../android"
# KMP codesigns when EXPANDED_CODE_SIGN_IDENTITY is present (inherited from
# Xcode), including as an empty string. Empty → codesign --sign "" →
# "no identity found". Nested Gradle also cannot use the Mac Mini keychain
# (errSecInternalComponent). Unset so Gradle only embeds; this script signs
# with Xcode's identity afterward when signing is allowed.
env -u EXPANDED_CODE_SIGN_IDENTITY \
  CODE_SIGNING_ALLOWED=NO \
  ./gradlew :markdown-core:embedAndSignAppleFrameworkForXcode

framework="$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH/OpenCodeMarkdownCore.framework"
if [ "${CODE_SIGNING_ALLOWED:-YES}" != "NO" ] && [ -d "$framework" ]; then
  identity="${EXPANDED_CODE_SIGN_IDENTITY:-}"
  if [ -n "$identity" ]; then
    /usr/bin/codesign --force --sign "$identity" --generate-entitlement-der "$framework"
  fi
fi

grammar_source="$SRCROOT/../../shared/markdown-core/src/commonMain/resources/textmate-grammars"
grammar_dest="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/textmate-grammars"
rm -rf "$grammar_dest"
mkdir -p "$(dirname "$grammar_dest")"
cp -R "$grammar_source" "$grammar_dest"
