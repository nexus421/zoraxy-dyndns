# Usage

## The plugin page

Zoraxy shows it under Plugins, DynDNS Whitelist. It follows Zoraxy's light and dark mode.

- **Setup in Zoraxy**: a short guide for the DynDNS host in Zoraxy, open until the first client exists, the same
  steps as [Deployment](deployment.md#zoraxy-setup).

- **DynDNS host**: the Zoraxy host with the tag `dyndns`. Only used to fill in the router settings.
- **Clients**: per client the last answer in words (green "updated" for `good` and "unchanged" for `nochg`, yellow
  "wrong password" for `badauth` and "invalid address" for `badrequest`, red "Zoraxy error" for `911`, the answer
  itself shows on hover) with its time, the access rules, the last addresses and the time of the last update. The
  answers are kept in memory only: after a restart a client shows as "reported" until its next report. A wrong
  password of a known client shows as "wrong password", the most common mistake when setting up a router. "Add client" asks for a name and the access
  rules, "Rules" changes them, "New password" replaces the password, "Delete" removes the client and its whitelist
  entries. Replacing and deleting ask for a second click.
- **Router settings**: after "Add client" and "New password" the page shows the password once, together with the
  FRITZ!Box fields and the values for other dyndns2 clients, each with a Copy button. Without HTTPS the browser has
  no clipboard access, then Copy selects the text and Ctrl+C copies it.
- **Access rules**: mode, whitelist switch, number of entries, the clients using the rule, warnings and the fixed
  entries, see [Configuration](configuration.md).
- **Recent activity**: the last 100 log lines of the plugin (updates, answers, changes made on the page, warnings),
  newest first. Kept in memory only, a restart of the plugin starts it empty, the card says so. The full history is in
  Zoraxy's log.

## FRITZ!Box

*Internet, Freigaben, DynDNS, Benutzerdefiniert*, the page shows these values ready to copy:

| Field                   | Value                                                                                         |
|-------------------------|-----------------------------------------------------------------------------------------------|
| Update-URL              | `https://<username>:<pass>@dyndns.example.com/nic/update?hostname=<domain>&myip=<ipaddr>,<ip6lanprefix>` |
| Domainname              | `dyndns.example.com`                                                                          |
| Benutzername / Kennwort | client name and the password shown once on the plugin page                                    |

The FRITZ!Box fills in `<username>`, `<pass>`, `<domain>`, `<ipaddr>` and `<ip6lanprefix>` itself. Without IPv6 in
the home network `<ip6lanprefix>` stays empty, which the plugin ignores.

## Other clients

Anything that speaks dyndns2 works, ddclient, inadyn and OPNsense included: server `dyndns.example.com`, path
`/nic/update`, Basic Auth with the client name and password, HTTPS.

Each update **replaces** all addresses of that client. Clients that send IPv4 and IPv6 in separate requests
(inadyn, pfSense, OpenWrt with two entries) should be configured as two clients, e.g. `pfsense-v4` and
`pfsense-v6`.

## Parameters

| Parameter                 | Holds                                          |
|---------------------------|------------------------------------------------|
| `myip`                    | IPv4 and/or IPv6, comma separated              |
| `myipv4`, `ip`            | IPv4                                           |
| `myipv6`, `myip6`, `ipv6` | IPv6                                           |

At most one IPv4 and one IPv6 per update. Without any of them the source address of the request is used. IPv4 must
be a public single address, IPv6 a global host or prefix /48 to /128. Host bits of a prefix are cleared, so
`2003:d5:df4a:a5ff::1/56` becomes `2003:d5:df4a:a500::/56`.

## Answers

Answers follow dyndns2:

| Answer                | HTTP | Meaning                                                              |
|-----------------------|------|----------------------------------------------------------------------|
| `good <ip>`           | 200  | addresses changed and applied                                        |
| `nochg <ip>`          | 200  | same addresses as before, whitelists checked                         |
| `badauth`             | 401  | wrong name or password                                               |
| `badrequest <reason>` | 400  | invalid or private address, wrong parameter                          |
| `911`                 | 500  | a rule of the client could not be updated (e.g. unknown rule ID)     |

A query with broken percent-encoding (`%zz`) is rejected with 400 before the plugin looks at it.
