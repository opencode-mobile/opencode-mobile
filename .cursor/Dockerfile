# Cursor Cloud development image for opencode-mobile.
#
# Captures the Android + Node toolchain so background agents start fast and
# reproducibly. iOS is intentionally not covered: it requires macOS + Xcode and
# cannot be built on Linux (use the macOS GitHub Actions workflows for iOS).
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# Base tools + JDK 17 (the Android Gradle build pins jvmToolchain(17)).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        wget \
        unzip \
        git \
        openjdk-17-jdk \
    && rm -rf /var/lib/apt/lists/*

# Node.js 22 for the repo tooling under scripts/ (icons, OpenAPI, fixtures).
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk

# Android SDK: command-line tools + platform 36 / build-tools 36.0.0 (matches
# android/app/build.gradle.kts compileSdk = 36).
ARG ANDROID_CMDLINE_TOOLS_VERSION=14742923
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && cd /tmp \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -O cmdtools.zip \
    && unzip -q cmdtools.zip -d /tmp/cmdtools \
    && mv /tmp/cmdtools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf cmdtools.zip /tmp/cmdtools \
    && yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null \
    && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
        "platform-tools" \
        "platforms;android-36" \
        "build-tools;36.0.0" > /dev/null \
    && chmod -R a+rX "${ANDROID_HOME}"

ENV PATH=${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/cmdline-tools/latest/bin:${JAVA_HOME}/bin:${PATH}
