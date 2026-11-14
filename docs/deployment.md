# Deployment

## Installation

Requirements: Linux amd64 or arm64 with glibc 2.34 or newer (Debian 12+, Ubuntu 22.04+), Zoraxy 3.3.3 or newer
(tested with 3.3.3, 3.3.4 and 3.3.5). Zoraxy's Docker image (Alpine, musl) is not supported.

From the plugin store, once it is listed there: Plugins, App Store, DynDNS Whitelist. Zoraxy downloads the binary
and the icon itself.

By hand: the binary must be named like its folder in Zoraxy's plugin directory.

```bash
mkdir -p /opt/zoraxy/plugins/zoraxy-dyndns
```

```bash
curl -L -o /opt/zoraxy/plugins/zoraxy-dyndns/zoraxy-dyndns https://github.com/nexus421/zoraxy-dyndns/releases/latest/download/dyndns_linux_amd64
```

```bash
chmod +x /opt/zoraxy/plugins/zoraxy-dyndns/zoraxy-dyndns
```

Use `dyndns_linux_arm64` on arm64. Every release carries a `.sha256` file next to each binary. It names the
release file, so compare the sum itself with the installed binary:

```bash
echo "$(curl -sL https://github.com/nexus421/zoraxy-dyndns/releases/latest/download/dyndns_linux_amd64.sha256 | cut -d' ' -f1)  /opt/zoraxy/plugins/zoraxy-dyndns/zoraxy-dyndns" | sha256sum -c
```

Restart Zoraxy and enable the plugin. The log shows `Config validated against N access rule(s)`. Then open the
plugin's page and add the clients, see [Usage](usage.md#the-plugin-page).

## Zoraxy setup

1. **Access rule** for the protected service, e.g. `home-only`, attached to the service's host. In `enforce` the
   plugin switches the whitelist on for you.
2. **DynDNS host**, e.g. `dyndns.example.com`, with a DNS record pointing to Zoraxy:
   - access rule **without** whitelist (e.g. `default`), otherwise the router is blocked before the plugin sees it
   - upstream an unused address such as `127.0.0.1:1`, so every other path fails. Zoraxy refuses a rule without
     target ("endpoint not defined"). `127.0.0.1` never leaves the server, and TCP port 1 belongs to TCPMUX, which
     practically no system runs, so every other path ends in an error (Zoraxy answers 521). Check it on your
     server with `ss -ltn 'sport = :1'`, which must print no listener
   - rate limiting on: it runs before the plugin and stops password guessing
   - tag `dyndns`
3. **Plugins, tag groups**: add the plugin to the tag `dyndns`.

Zoraxy hands a request to the plugin after the access rule, rate limit and its own login of the host, and before the
upstream. A separate DynDNS host is therefore not strictly needed: the tag `dyndns` on an existing host also sends
its `/nic/update` to the plugin while every other path still reaches that host's backend. That host must have no
whitelist and no Zoraxy login, so a separate host is the safer choice.

## TLS

Use HTTPS, the router sends its password with every update. With TLS enabled, Zoraxy never routes plain HTTP to
plugins, so a plain request never reaches the plugin. If Zoraxy itself runs without TLS behind another proxy that
terminates TLS, the plugin works and logs a warning on the first plain request.

## Updates

Replace the binary, restart the plugin in Zoraxy. `config.json` and `state.json` stay. From 0.1.0: a `config.json`
that is still the unedited template of 0.1.0 is ignored, set the plugin up on its page. The Zoraxy plugin store
finds new releases through `.releaseurl` and `.introspect` in this repository.
