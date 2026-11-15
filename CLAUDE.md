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
- `bayern.kickner` libraries from `https://maven.kickner.bayern/releases`: Klogger 0.5.0. kotlinx.html 0.12.0 for
  the plugin page, kotlinx.serialization 1.11.0 for every JSON.
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
- Four threads answer requests, changes to state, config and whitelists stay one after another at the locks of
  `Reconciler` and `ConfigStore`. A client gets 5 seconds to send its request (`limitRequestTime`, only
  `maxReqTime`, never `maxRspTime`, which would cut slow answers). Never add a delay to failed logins, Zoraxy's rate
  limit handles guessing.
- Files the plugin writes are owner only (`writeOwnerOnly`).
- Config changes go through `ConfigStore.update`, which validates before it writes. Passwords are generated
  (`newPassword`), shown once in the answer page and stored only as SHA-256.

Plugin page rules (Zoraxy loads it in an iframe with `sandbox="allow-scripts allow-same-origin"`):

- HTML only through kotlinx.html in `Page.kt`, never raw HTML. No inline script, no `<style>`, no `style=`
  attribute, the CSP in `Server.kt` forbids them. CSS and JS live as Kotlin strings in `Assets.kt`.
- Without `allow-forms` the browser drops a form submission before any `submit` event fires: `app.js` catches
  clicks on submit buttons and Enter in inputs and posts with `fetch`, the CSRF token from `<meta name="csrf-token">`
  in `X-CSRF-Token`. Zoraxy checks that token before the plugin sees the POST.
- Without `allow-modals` there is no `confirm()`: ask with `data-confirm` (second click).
- Paths in the page are relative (Zoraxy serves it under `/plugin.ui/<id>/`), only the icon points to Zoraxy's
  `/api/plugins/icon`.
- Check page changes in the real Zoraxy iframe, light and dark, desktop and phone width.

## Code style

Same conventions as [Dirl](https://github.com/nexus421/Dirl) and
[DemoAiProject](https://github.com/nexus421/DemoAiProject): `.not()` instead of `!`, small `if`s without braces,
`runCatching` by default, expected outcomes as `Result` and sealed types instead of exceptions, injectable `clock`,
`staticLog` with `private const val TAG` in top-level code and receiver helpers (`infoLog { }`) in classes,
timestamps through `timestampFormat` in `Common.kt`, KDoc on every public declaration. No em dashes, no en dashes
and no semicolons in prose (README, KDoc, comments, log messages, the plugin page). Everything is English.

Deliberate deviation from DemoAiProject: Kotlin's `Result` instead of KotNexLib's `ResultOf2`, KotNexLib would pull
BouncyCastle into the native image.

Keep the README short: no implementation details there, they belong into docs/. Update docs/ and KDoc in the same
change as the code.
