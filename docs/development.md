# Development

Requirements: JDK 25 (Amazon Corretto is the pinned toolchain, Gradle downloads it if missing), GraalVM 25 for the
native image. Always use the Gradle wrapper.

## Build and test

```bash
./gradlew build
```

`build` compiles, runs the unit tests and builds the fat JAR `build/libs/zoraxy-dyndns.jar`, the input of the native
image:

```bash
native-image -jar build/libs/zoraxy-dyndns.jar -o build/zoraxy-dyndns
```

The flags (`--no-fallback -Os --enable-url-protocols=http`) come from
[native-image.properties](../src/main/resources/META-INF/native-image/bayern.kickner/zoraxy-dyndns/native-image.properties)
in the JAR. `--enable-url-protocols=http` is needed for the calls to Zoraxy's API on `http://127.0.0.1`. No
reflection or resource config is needed: Klogger and kotlinx.serialization (generated serializers) work without
reflection.

`.introspect` in the repository root must be exactly what the binary prints, the Zoraxy plugin store reads it from
there. After a version or introspect change:

```bash
./build/zoraxy-dyndns -introspect > .introspect
```

## End-to-end test

```bash
sudo test/e2e.sh <zoraxy-binary> build/zoraxy-dyndns
```

[e2e.sh](../test/e2e.sh) sets up a real Zoraxy from scratch in a temporary directory (access rules, hosts, tag
group, plugin) and configures the plugin through its page, with Zoraxy's login cookie and CSRF token, as a browser
would. It runs 84 checks: a POST without CSRF token is rejected, clients and rules created on the page,
authentication, the update cycle, input validation, dual stack and parameter aliases, the three modes, fixed
entries, stalled requests next to a real update (on the plugin's port and through Zoraxy), a DNS-rebinding request
on the plugin's port, the reconcile loop, deleting clients and releasing rules on the page, a config edited by hand,
restarts, the page with its activity card, `/term`, owner only files and that no password ends up in a log or file.
It needs curl, python3, openssl and root for port 80 (the plain HTTP case), and takes about three minutes because it
waits for one reconcile cycle.

## CI and release

[build.yml](../.github/workflows/build.yml) runs on every push and pull request, on amd64 and arm64: unit tests,
native image, the `.introspect` check and, on amd64, the end-to-end test against the Zoraxy release pinned in the
workflow (download checked against its SHA-256).

Release:

1. Set `version` in `build.gradle.kts` (plain `major.minor.patch`, the build rejects anything else because the
   introspect carries three numbers), rebuild, update `.introspect`, commit and push.
2. Publish a release on GitHub with the tag `v<version>`, e.g. `v1.1.0` for `version = "1.1.0"`.

[release.yml](../.github/workflows/release.yml) then checks that the tag matches the version, runs the whole build
and attaches `dyndns_linux_amd64` and `dyndns_linux_arm64` with their `.sha256` files. The names are what the
Zoraxy plugin store downloads: `.releaseurl` plus the last segment of the plugin ID, OS and architecture.

The actions in both workflows are pinned to commit hashes, the comment names the version. The arm64 job runs on
GitHub's `ubuntu-24.04-arm` runners, which are free for public repositories only. `gradle/actions/setup-gradle` v6
keeps its caching in a closed-source component under the Gradle Terms of Use (https://gradle.com/legal/terms-of-use/),
using it means accepting them.

## Updating dependencies

Exact versions of every dependency, transitive ones included, are locked in `gradle.lockfile`. After a version
change:

```bash
./gradlew dependencies --write-locks
```

## Project layout

One package `bayern.kickner.zoraxy.dyndns`:

| File            | Content                                                                         |
|-----------------|---------------------------------------------------------------------------------|
| `Main.kt`       | Arguments, logging, wiring, reconcile loop                                      |
| `Common.kt`     | Timestamps, JSON settings, SHA-256, log lines, owner only atomic writes         |
| `Introspect.kt` | The self-description for Zoraxy and the plugin store                            |
| `Config.kt`     | `config.json` model, strict parsing, validation, JSON for writing               |
| `ConfigStore.kt`| The current config, validated and written on every change                       |
| `UiActions.kt`  | The changes of the plugin page: clients, passwords, rules, DynDNS host          |
| `Page.kt`       | The plugin page, rendered with kotlinx.html                                     |
| `Assets.kt`     | `app.css` and `app.js` as Kotlin strings, so the native image needs no resources |
| `ActivityLog.kt`| The last 100 log lines for the page, fed by Klogger                             |
| `State.kt`      | `state.json`: last report per client, managed rules, atomic write               |
| `Cidr.kt`       | IP and prefix parsing, public address check, canonical form                     |
| `Params.kt`     | dyndns2 query parameters and their aliases                                      |
| `Planner.kt`    | Pure planning: desired entries, what to add, remove and switch per rule         |
| `Reconciler.kt` | Applies the plans through the API, cleanup, status for the page                 |
| `ZoraxyApi.kt`  | Client for Zoraxy's plugin API                                                  |
| `Server.kt`     | Local HTTP server: dyndns2 endpoint, Basic Auth, plugin page and actions, `/term` |

Conventions follow [Dirl](https://github.com/nexus421/Dirl) and
[DemoAiProject](https://github.com/nexus421/DemoAiProject): `.not()` instead of `!`, `runCatching` by default,
expected outcomes as return values, Klogger for logging, KDoc on every public declaration, no em dashes, en dashes
or semicolons in prose. Deliberate deviation: expected outcomes are Kotlin's `Result` instead of KotNexLib's
`ResultOf2`. KotNexLib would pull BouncyCastle into a native image that needs no cryptography beyond SHA-256.

## Verification status

Verified:

- 171 unit tests: address parsing and the public range checks, the canonical IPv6 form, query parameters and their
  aliases, config parsing and validation (strict keys, modes, fixed entries, names, hashes, the DynDNS host),
  writing `config.json` (round trip, owner only, an invalid change writes nothing), `state.json` (round trip, owner
  only, broken files, single invalid entries), every planner case of the three modes, parsing of Zoraxy's access
  list, log lines, the activity log and the introspect payload. Every action of the plugin page (valid, invalid,
  stale, unknown rules, Zoraxy unreachable, a failed reconcile after saving), the page itself (escaping, no inline
  script or style, the password shown once, the placeholder host) and the HTTP server (stalled requests and
  kept-alive connections included) run against
  [FakeZoraxy](../src/test/kotlin/bayern/kickner/zoraxy/dyndns/FakeZoraxy.kt), an in-memory plugin API.
- The end-to-end test, locally with the JAR on the JVM inside a sandbox without network: all 79 checks of that time
  against Zoraxy 3.3.3, 3.3.4 and 3.3.5 (`v3.3.5-rc5`), and all 84 checks of today against 3.3.5. CI runs it against
  3.3.5 with the native binary.
- The plugin page clicked through in the real Zoraxy iframe: adding a client, the router settings, Copy without
  HTTPS, Enter in a field, an invalid fixed entry, deleting with the second click, light and dark mode following
  Zoraxy's switch live, phone width, no CSP violation in the console.

The native image is built and tested in CI, not locally.
