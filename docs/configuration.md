# Configuration

## Command line

Zoraxy starts the plugin itself with `-configure=<json>` (port, API key). Two flags are for you:

| Flag          | Does                                                                         |
|---------------|------------------------------------------------------------------------------|
| `-hash`       | Reads a password (hidden on a terminal, or one line from stdin), prints its SHA-256 for `password_sha256`. At least 16 characters |
| `-introspect` | Prints the self-description Zoraxy reads before it starts the plugin         |

## config.json

`config.json` lives next to the binary, Zoraxy starts the plugin in its folder. On the first start the plugin
writes a template (owner only, `0600`) and runs without clients. An unedited template stops the plugin on the next
start, the log says why.

```json
{
  "clients": [
    { "name": "fritzbox-home", "password_sha256": "<hash>", "rules": ["<rule-id-1>", "<rule-id-2>"] }
  ],
  "rules": {
    "<rule-id-1>": { "mode": "enforce", "static": ["203.0.113.10", "10.8.0.0/24"] },
    "<rule-id-2>": { "mode": "open_when_empty" }
  }
}
```

- **clients**: any number. `name` is the Basic Auth user name (letters, digits, `.`, `_`, `-`, at most 64, `static`
  is reserved), `password_sha256` the output of `-hash`, `rules` one or more access rule IDs from Zoraxy. Several
  clients may share a rule.
- **rules** (optional): per access rule the mode and fixed entries. A rule that is not listed runs as `enforce`
  without fixed entries. A rule listed here without any client is managed as well, with its fixed entries only.

The file is read strictly: an unknown key, a wrong type or an invalid value stops the plugin with a message in the
Zoraxy log, so a typo never silently falls back to a default. Changes take effect when the plugin restarts.

The access rule ID is shown in Zoraxy under Access Control, it is not the rule's name.

## Modes

| Mode                | Whitelist has entries        | Whitelist is empty                               |
|---------------------|------------------------------|--------------------------------------------------|
| `enforce` (default) | plugin keeps it **on**       | on, everything blocked                           |
| `warn`              | plugin never touches the switch, only warns while it is off | switch stays as it is |
| `open_when_empty`   | plugin keeps it on           | plugin switches it **off, everything allowed**   |

"Empty" means no IP and no country entries at all, manual ones included. `open_when_empty` is dangerous and must be
set explicitly. A single fixed entry keeps the whitelist closed. If the plugin stops while an `open_when_empty` rule
is open, it stays open until the plugin runs again.

In `enforce` the switch belongs to the plugin: turning the whitelist off in the Zoraxy UI is reverted within 60
seconds.

## Fixed entries

`static` holds entries that are always whitelisted, such as an emergency IP, a VPN range or an office. Private
ranges are allowed, IPv4 /8 to /32, IPv6 /16 to /128. Nothing wider, that would practically switch the whitelist
off. They carry the comment `dyndns:static`.

## Entries the plugin owns

Every entry the plugin writes carries the comment `dyndns:<client>` or `dyndns:static`. Only those entries are ever
changed or removed. Entries with any other comment, the ones you add by hand, are left alone. If a manual entry
already holds an address a client reports, the plugin keeps the manual entry and logs a warning.

Do not give your own entries a comment starting with `dyndns:`, the plugin would treat them as its own.

## state.json

Written only by the plugin, next to `config.json` (owner only, atomic rename). It holds the last report of every
client and every rule the plugin has ever managed. That is how it cleans up after a client or a rule was removed
from `config.json`, and how the whitelists survive a restart of Zoraxy or the plugin. A missing or broken file means
an empty state: the clients report again with their next update. Deleting it is safe.

## Warnings

The log and the status page warn about settings that weaken a whitelist:

- whitelist off in `warn` or `open_when_empty`
- "allow local and loopback" on: private networks bypass the whitelist
- country entries: they allow whole countries
- access rule IDs in `config.json` that Zoraxy does not know

Each warning is logged once and again only when it changes.
