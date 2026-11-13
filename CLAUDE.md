# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

zoraxy-dyndns, a Zoraxy plugin (Kotlin, GraalVM native image) that turns Zoraxy into a dyndns2 endpoint and keeps
the reported IPs in the whitelists of Zoraxy access rules. [README.md](README.md) is the short project page, the
details live in [docs/](docs): usage, configuration, deployment, security and development (build, tests, release,
project layout, verification status). Read [docs/security.md](docs/security.md) before changing security-relevant
code (authentication, address validation, what the plugin touches in Zoraxy).

## Commands

Use the Gradle wrapper.

```bash
./gradlew build
```

```bash
native-image -jar build/libs/zoraxy-dyndns.jar -o build/zoraxy-dyndns
```

```bash
sudo test/e2e.sh <zoraxy-binary> build/zoraxy-dyndns
```

The end-to-end test needs a Zoraxy binary, curl, python3, openssl and root for port 80, see docs/development.md.
JUnit silently skips test methods that do not return `Unit`, so an expression body must end in an assertion that
returns `Unit` (`assertEquals`, `assertTrue`), never `assertFailsWith`.

Dependencies are locked (`gradle.lockfile`). After a version change run `./gradlew dependencies --write-locks`.

`.introspect` must be exactly the output of `-introspect`, CI diffs them. Regenerate it after changing the version
or `Introspect.kt`.

## Toolchain

- Kotlin 2.4.21, Gradle 9.9.1. Toolchain Amazon Corretto 25, GraalVM CE 25 for `native-image`.
- `bayern.kickner` libraries from `https://maven.kickner.bayern/releases`: Klogger 0.5.0. kotlinx.serialization
  1.11.0 for every JSON.
- Only reflection-free dependencies, the native image has no reflection or resource config. Check a new dependency
  with a native build before adding it.
- `BuildConfig` (`VERSION`, `BUILD_TIME`) is generated into `build/` on every build, never commit it. The introspect
  version comes from `VERSION`.

## Architecture

`Main.kt` reads the configure spec from Zoraxy, loads `config.json`, wires `ZoraxyApi`, `Reconciler` and `Server`
and starts the reconcile loop. `Planner.kt` is pure and holds every decision, `Reconciler` only applies plans.

Invariants, keep them when adding code:

- Only rules from `config.json` or `state.json` `managed_rules` are touched, and in them only entries whose comment
  starts with `dyndns:` (`OWNED_PREFIX`).
- Add before remove, so a changing IP never causes a lockout window.
- Reported addresses go through `Cidr.parse` (public only), fixed entries through `Cidr.parseStatic`. Never resolve
  host names.
- Never log passwords, the API key or query values. Every log line goes through `logLine` (the Klogger format),
  which strips control characters. Never log stack traces, they would lose their line breaks there.
- The server handles requests one after another. Never add a delay to failed logins, Zoraxy's rate limit handles
  guessing.
- Files the plugin writes are owner only.

## Code style

Same conventions as [Dirl](https://github.com/nexus421/Dirl) and
[DemoAiProject](https://github.com/nexus421/DemoAiProject): `.not()` instead of `!`, small `if`s without braces,
`runCatching` by default, expected outcomes as `Result` and sealed types instead of exceptions, injectable `clock`,
`staticLog` with `private const val TAG` in top-level code and receiver helpers (`infoLog { }`) in classes,
timestamps through `timestampFormat` in `Common.kt`, KDoc on every public declaration. No em dashes, no en dashes
and no semicolons in prose (README, KDoc, comments, log messages, the status page). Everything is English.

Deliberate deviation from DemoAiProject: Kotlin's `Result` instead of KotNexLib's `ResultOf2`, KotNexLib would pull
BouncyCastle into the native image.

Keep the README short: no implementation details there, they belong into docs/. Update docs/ and KDoc in the same
change as the code.
