# Session Repository — Decisions

## Repository boundary

`SessionRepository` covers the Sessions API group only. `MessageRepository` is a follow-up slice.

## OpenAPI source

- `npm run api:fetch` saves live `GET /doc` to `build/openapi/opencode-doc.json`.
- [`api/opencode-sessions.openapi.yaml`](../../../api/opencode-sessions.openapi.yaml) documents the Sessions routes because live `/doc` currently omits `/session/*` paths.
- `npm run api:generate` can emit a Kotlin client from that YAML; **v1 ships hand-written Ktor / URLSession clients** aligned with the YAML until codegen is wired into Gradle/Xcode.

## Dependency injection

- **Android:** Dagger Hilt (`@HiltAndroidApp`, `NetworkModule`, `SessionModule`, `@HiltViewModel`).
- **iOS:** [Factory](https://github.com/hmlongco/Factory) with `AppContainer` registrations.

### Android toolchain note

AGP 9 + Kotlin 2.3 metadata is not yet supported by Hilt 2.57. The app module uses
Kotlin **2.2.21**, KSP **2.2.21-2.0.5**, and `android.newDsl=false` /
`android.builtInKotlin=false` in `android/gradle.properties` until Hilt supports
built-in Kotlin on AGP 9.

## Connection

`ServerConnection` holds base URL and Basic Auth credentials. Runtime modules
resolve it from the persisted active Connection Profile; URL and username use
ordinary preferences while the password uses iOS Keychain or Android
Keystore-backed storage. `AppConfig` is no longer the runtime source after the
[Server Connection](../server-connection/decisions.md) slice.

## Permission API

`respondToPermission` does not expose `remember` to match v1 mobile policy in CONTEXT.md.

## Tests

Automated: home Screen Model unit tests with fake `SessionRepository`. Manual: local `opencode serve`.
