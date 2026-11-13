# Security

What the plugin protects, what it relies on, and where its limits are.

## The whitelist is a second layer

Anyone with a client password can whitelist their own IP. Keep a login on the protected service itself.

## Passwords

`config.json` stores the SHA-256 of each password only, compared in constant time. That is enough because the
passwords are random: `openssl rand -hex 24` gives 192 bits, and `-hash` refuses anything shorter than 16
characters. Do not use a password you made up, a fast hash does not protect a guessable one. Hex is URL-safe, so
it also works in the FRITZ!Box update URL.

A wrong user name costs the same hash as a wrong password, so the timing does not reveal valid names. Passwords
never appear in the log: a rejected update logs only the names of its query parameters, because a misconfigured
client may put its password there.

## Password guessing

The plugin does not slow down failed logins: it handles requests one after another, a delay would let anyone stall
real updates. Zoraxy's rate limit on the DynDNS host runs before the plugin sees a request and stops guessing, see
[Deployment](deployment.md#zoraxy-setup). The end-to-end test checks that it answers 429.

## Transport

Use HTTPS. With TLS enabled, Zoraxy never routes plain HTTP to plugins, the end-to-end test checks that a plain
request changes nothing. A request that reached Zoraxy without TLS (`X-Forwarded-Proto: http`) is logged once as a
warning, for setups with TLS terminated in front of Zoraxy.

## What the plugin may touch

The plugin talks only to Zoraxy's plugin API on `127.0.0.1`, with the key Zoraxy generated for it, never through a
system proxy. The key is limited to the four endpoints in `.introspect`: list access rules, add and remove a
whitelist IP, switch a whitelist. Technically that key could change every whitelist. The plugin itself only touches
rules listed in `config.json` or remembered in `state.json`, and in those only entries with a comment starting with
`dyndns:`.

## Input validation

Everything a client sends is untrusted:

- Addresses are parsed as IP literals only, never resolved through DNS. Legacy forms like `1.2.3`, scoped addresses
  and host names are rejected.
- Reported IPv4 must be a single public address. Private, loopback, CGNAT, link-local, documentation, multicast and
  reserved ranges are rejected. Reported IPv6 must be global unicast (`2000::/3` without `2001:db8::/32`) with a
  prefix of /48 or longer, so a client can never open more than one site's prefix.
- At most one IPv4 and one IPv6 per update.
- Every log line is stripped of control and formatting characters and cut at 1000 characters, in one place (the
  log format). Text from a request, from Zoraxy or from an exception can never break a line or forge another one.
- The status page escapes every value and is sent with `Content-Security-Policy: default-src 'none'; style-src
  'unsafe-inline'`. Every answer has `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`.

## Files

`config.json` (template) and `state.json` are written owner only (`0600`), `state.json` atomically through a
temporary file and a rename. Both are read strictly, an unknown key in `config.json` stops the plugin. A broken
`state.json` is replaced by an empty state instead of stopping the plugin.

## Local access

The plugin listens on `127.0.0.1` on a port Zoraxy assigns, like every Zoraxy plugin. Local users on the Zoraxy host
can reach it directly: they can read the status page (client names and their addresses), stop the plugin through
`/ui//term`, and send updates, which still need a client password. Zoraxy also passes the API key on the command
line (`-configure=<json>`), so it is visible in the process list. Run Zoraxy on a host without untrusted local
users.

## Known limits

- `open_when_empty` opens the whitelist for everyone while it has no entries, and stays open if the plugin stops
  in that state.
- "Allow local and loopback" and country entries in a rule bypass or widen its whitelist. The plugin warns about
  both but does not change them.
- A manual entry with a comment starting with `dyndns:` is treated as the plugin's own and may be removed.
