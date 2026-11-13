# Usage

## FRITZ!Box

*Internet, Freigaben, DynDNS, Benutzerdefiniert*:

| Field                   | Value                                                                                         |
|-------------------------|-----------------------------------------------------------------------------------------------|
| Update-URL              | `https://<username>:<pass>@dyndns.example.com/nic/update?hostname=<domain>&myip=<ipaddr>`     |
| Domainname              | `dyndns.example.com`                                                                          |
| Benutzername / Kennwort | client name and password from `config.json`                                                   |

With IPv6 in the home network, whitelist the LAN prefix as well: `myip=<ipaddr>,<ip6lanprefix>`.

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

## Status page

Zoraxy shows the plugin's page under Plugins: per client the last addresses, the time of the last update and the
last answer, per access rule the mode, the whitelist switch, the number of entries and the current warnings.
