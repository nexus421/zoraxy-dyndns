# Security

What the plugin protects, what it relies on, and where its limits are.

## The whitelist is a second layer

Anyone with a client password can whitelist their own IP. Keep a login on the protected service itself.

## Passwords

The plugin page generates every password from 24 random bytes (`SecureRandom`, 192 bits, hex) and shows it exactly
once, in the answer to "Add client" or "New password". `config.json` stores only its SHA-256, compared in constant
time. A fast hash is enough because the passwords are random, nobody can pick a guessable one. Hex is URL-safe for
router settings. Passwords and hashes are never logged.

"New password" replaces the password at once, but the address the client reported last stays whitelisted until
the router reports again with the new password. That keeps a home network reachable that rarely changes its IP. If
a password leaked, delete the client and add it again: that removes its addresses at once.

A wrong user name costs the same hash as a wrong password, so the timing does not reveal valid names. Passwords
never appear in the log: a rejected update logs only the names of its query parameters, because a misconfigured
client may put its password there.

## Password guessing

The plugin does not slow down failed logins: a delay would hold one of its four threads and let anyone stall real
updates. A request that announces a body the plugin does not read, for example an update with a body it never sends,
holds no thread: the plugin answers and closes the connection instead of waiting for the rest. A client that stalls
while sending its headers holds one thread for at most 5 seconds, the others keep answering. Through Zoraxy 3.3.5
neither reaches the plugin as a stall, the end-to-end test checks both paths. Changes still happen one after
another. Zoraxy's rate limit on the DynDNS host runs before the plugin sees a request and stops guessing, see
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
- The plugin page escapes every value (kotlinx.html) and is sent with `Content-Security-Policy: default-src 'none';
  script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self';
  frame-ancestors 'self'`: no inline script or style, only its own `app.js` and `app.css` and Zoraxy's icon, and only
  Zoraxy itself may frame the page. Every answer has
  `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`.

## Changes on the plugin page

Zoraxy forwards `/plugin.ui/<id>/` only after its login and checks its CSRF token (`gorilla/csrf`) on every POST
before the plugin sees it. The page's script sends that token with every change, a forged form from another site
fails at Zoraxy. On its own port the plugin serves the page and its changes only with the `X-Zoraxy-Csrf` header
that Zoraxy's admin proxy adds, and only when the `Host` header does not name the plugin's own port. Zoraxy's proxy
forwards the admin's original `Host`, the address of Zoraxy itself. A cross-site form or a request that another
service on the host can be tricked into sending (server-side request forgery) cannot set the header. A DNS-rebinding
page can set it, but it reaches the plugin only by naming the plugin's port. Both are refused. Every change
is validated with the same rules as loading `config.json`, an invalid one changes nothing. Each change is logged as
one `UI:` line, without passwords. A form body is limited to 64 KiB. Zoraxy stops the plugin by calling `/term`
directly on the plugin's port. The same path through the admin pages is refused, a GET
there needs no CSRF token and any link an admin follows could otherwise stop the plugin.

## Files

`config.json` and `state.json` are written owner only (`0600`), both atomically through a temporary file
and a rename. Both are read strictly, an unknown key in `config.json` stops the plugin. A broken
`state.json` is replaced by an empty state instead of stopping the plugin. That fails closed: the clients' entries
are removed until they report again, see [Configuration](configuration.md#statejson).

## Local access

The plugin listens on `127.0.0.1` on a port Zoraxy assigns, like every Zoraxy plugin. Local users on the Zoraxy host
can reach it directly: they can read and change the plugin page by setting the header Zoraxy adds (it is Zoraxy that
asks for the login, not the plugin), stop the plugin through `/ui//term`, and send updates, which still need a client
password. `/ui//term` needs no header, so another service on the host that can be made to fetch a URL can stop the
plugin as well. Zoraxy also passes the API key on the command
line (`-configure=<json>`), so it is visible in the process list. Run Zoraxy on a host without untrusted local
users.

## Known limits

- `open_when_empty` opens the whitelist for everyone while it has no entries, and stays open if the plugin stops
  in that state.
- "Allow local and loopback" and country entries in a rule bypass or widen its whitelist. The plugin warns about
  both but does not change them.
- A manual entry with a comment starting with `dyndns:` is treated as the plugin's own and may be removed.
