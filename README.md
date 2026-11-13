# zoraxy-dyndns

[![Build](https://github.com/nexus421/zoraxy-dyndns/actions/workflows/build.yml/badge.svg)](https://github.com/nexus421/zoraxy-dyndns/actions/workflows/build.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![GraalVM](https://img.shields.io/badge/GraalVM-native-F29111?logo=oracle&logoColor=white)
![Zoraxy](https://img.shields.io/badge/Zoraxy-3.3.3%2B-2E7D32)
![License](https://img.shields.io/badge/License-MIT-blue)

A [Zoraxy](https://github.com/tobychui/zoraxy) plugin that turns Zoraxy itself into a DynDNS endpoint. Routers and
DynDNS clients (FRITZ!Box, OPNsense, ddclient, inadyn, anything that speaks **dyndns2**) push their current IP, and
the plugin keeps it in the IP whitelists of the Zoraxy access rules you assign.

Use case: a service that should only be reachable from home or a few sites, although their IPs change. No external
DynDNS provider, no DNS record, no polling.

```
FRITZ!Box ──GET /nic/update?myip=…──▶ Zoraxy host "dyndns.example.com" (no whitelist, tag "dyndns")
                                          │ static capture /nic/update
                                          ▼
                                     plugin: Basic Auth, validate, state.json, reconcile
                                          │ /plugin/api/…
                                          ▼
                                     access rule "home-only" (whitelist) ◀── your protected service
```

## Features

- Any number of clients, each with its own password and one or more access rules
- IPv4 and IPv6, including the LAN prefix of a FRITZ!Box (`myip=<ipaddr>,<ip6lanprefix>`)
- Declarative: the whitelists follow `config.json` plus the last report of every client, at startup, after every
  report and every 60 seconds
- New addresses are added before old ones are removed, no lockout window
- Entries you add by hand are never touched, removed clients and rules are cleaned up
- Fixed entries per rule (emergency IP, VPN range, office), modes `enforce`, `warn` and `open_when_empty`
- Status page in Zoraxy's plugin UI, warnings for settings that weaken a whitelist
- One native binary built with GraalVM, starts in milliseconds, needs nothing besides glibc

## Status

zoraxy-dyndns does what it is meant to do. It is maintained: security fixes, bug fixes and dependency updates keep
coming, new features only rarely. Issues and bug reports are welcome.

## Quick start

Requirements: Linux amd64 or arm64 with glibc 2.34 or newer (Debian 12+, Ubuntu 22.04+), Zoraxy 3.3.3 or newer
(tested with 3.3.5). Zoraxy's Docker image (Alpine, musl) is not supported.

```bash
mkdir -p /opt/zoraxy/plugins/zoraxy-dyndns
```

```bash
curl -L -o /opt/zoraxy/plugins/zoraxy-dyndns/zoraxy-dyndns https://github.com/nexus421/zoraxy-dyndns/releases/latest/download/dyndns_linux_amd64
```

```bash
chmod +x /opt/zoraxy/plugins/zoraxy-dyndns/zoraxy-dyndns
```

Restart Zoraxy and enable the plugin. It writes a `config.json` template next to the binary. Create a password and
its hash:

```bash
openssl rand -hex 24
```

```bash
/opt/zoraxy/plugins/zoraxy-dyndns/zoraxy-dyndns -hash
```

Fill in `config.json`, restart the plugin and check that the log shows `Config validated against N access rule(s)`.
Then set up the DynDNS host in Zoraxy and the router, see [Deployment](docs/deployment.md) and [Usage](docs/usage.md).

## Documentation

- [Usage](docs/usage.md): clients like the FRITZ!Box, parameters, answers, status page
- [Configuration](docs/configuration.md): `config.json`, modes, fixed entries, `state.json`
- [Deployment](docs/deployment.md): installation, Zoraxy setup, TLS, updates
- [Security](docs/security.md): passwords, what the plugin may touch, input validation, known limits
- [Development](docs/development.md): build, tests, release, project layout, verification status

## Why zoraxy-dyndns

It does one thing: keep the changing IPs of a few sites in Zoraxy's whitelists. It is reduced to what that needs
and aims to be simple, stable, secure and reliable. The code stays small enough to review alone.

zoraxy-dyndns is written with AI. Code, tests and documentation were created with Claude by Anthropic, directed,
tested and spot-checked by me.

## License

[MIT](LICENSE), Copyright (c) 2026 Marvin Kickner.
