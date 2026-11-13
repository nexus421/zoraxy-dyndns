#!/usr/bin/env bash
# End-to-end test against a real Zoraxy instance, set up from scratch in a temp dir.
#
#   test/e2e.sh <path-to-zoraxy-binary> <path-to-plugin-binary>
#
# Needs: curl, python3, openssl. Uses ports 18000 (admin), 18080 (proxy, TLS), 80 (proxy, plain HTTP), 19000 (backend).
# Client IPs are simulated with X-Forwarded-For: the test access rules trust 127.0.0.1 as proxy.
# Runtime ~3 min (T42 waits for one 60 s reconcile cycle).
set -uo pipefail

ZORAXY_BIN=$(realpath "$1"); PLUGIN_BIN=$(realpath "$2")
ID=bayern.kickner.zoraxy.dyndns
WORK=$(mktemp -d); trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$WORK"' EXIT
PDIR="$WORK/plugins/zoraxy-dyndns"
PASS=0; FAIL=0

check() { # name expected actual
  if [[ "$3" == "$2" ]]; then echo "PASS  $1"; PASS=$((PASS+1)); else echo "FAIL  $1: expected '$2', got '$3'"; FAIL=$((FAIL+1)); fi
}
contains() { # name needle haystack
  if [[ "$3" == *"$2"* ]]; then echo "PASS  $1"; PASS=$((PASS+1)); else echo "FAIL  $1: '$2' not in '${3:0:300}'"; FAIL=$((FAIL+1)); fi
}

C=(--noproxy '*' -sk)
admin() { # path [curl args]
  local token; token=$(curl "${C[@]}" -c "$WORK/jar" -b "$WORK/jar" http://127.0.0.1:18000/ | grep -oP 'zoraxy.csrf.Token" content="\K[^"]+')
  local p=$1; shift
  curl "${C[@]}" -c "$WORK/jar" -b "$WORK/jar" -H "X-CSRF-Token: $token" -H "Origin: http://127.0.0.1:18000" \
    -H "Referer: http://127.0.0.1:18000/" "http://127.0.0.1:18000$p" "$@"
}
R=(--resolve secret.test:18080:127.0.0.1 --resolve dyndns.test:18080:127.0.0.1 --resolve open.test:18080:127.0.0.1 --resolve dyndns.test:80:127.0.0.1)
host() { curl "${C[@]}" "${R[@]}" -o /dev/null -w '%{http_code}' -H "X-Forwarded-For: $2" "https://$1:18080/"; } # host ip
secret() { host secret.test "$1"; }
# sleep: the DynDNS host has Zoraxy's rate limit (10 req/s) enabled, as recommended; T33 tests it on purpose
upd() { sleep 0.2; curl "${C[@]}" "${R[@]}" -w ' %{http_code}' "https://$1@dyndns.test:18080/nic/update$2"; }
wl() { admin "/api/whitelist/list?type=ip&id=${1:-$RID}" | python3 -c 'import json,sys; print(" ".join(sorted(e["IP"]+"="+e["Comment"] for e in json.load(sys.stdin))))'; }
enabled() { admin /api/whitelist/enable -X POST -d id="$1"; }
rule_id() { admin /api/access/list | python3 -c "import json,sys; print([r['ID'] for r in json.load(sys.stdin) if r['Name']=='$1'][0])"; }
plugin_log() { grep -a '\[DynDNS Whitelist:' "$WORK/zoraxy.log"; }
start_zoraxy() {
  (cd "$WORK" && exec ./zoraxy -port=:18000 -noauth -mdns=false -default_inbound_port=18080 -default_inbound_enabled=true -fastgeoip >> zoraxy.log 2>&1) &
  ZPID=$!
  for _ in $(seq 60); do curl "${C[@]}" -o /dev/null http://127.0.0.1:18000/ && return; sleep 0.5; done
  echo "Zoraxy did not start"; exit 1
}
restart_plugin() { admin /api/plugins/disable -d plugin_id=$ID >/dev/null; sleep 1; admin /api/plugins/enable -d plugin_id=$ID >/dev/null; sleep 4; }
write_config() { # json
  echo "$1" > "$PDIR/config.json"
}

# --- setup ---------------------------------------------------------------------------------------
cp "$ZORAXY_BIN" "$WORK/zoraxy"; mkdir -p "$PDIR" "$WORK/backend"
cp "$PLUGIN_BIN" "$PDIR/zoraxy-dyndns"
echo "SECRET OK" > "$WORK/backend/index.html"
(cd "$WORK/backend" && exec python3 -m http.server 19000 --bind 127.0.0.1 >/dev/null 2>&1) &

contains "T01 introspect returns the plugin id" "\"id\": \"$ID\"" "$("$PLUGIN_BIN" -introspect)"

start_zoraxy
for n in home-only warn-rule open-rule static-rule; do admin /api/access/create -d name=$n -d desc=e2e >/dev/null; done
RID=$(rule_id home-only); RID2=$(rule_id warn-rule); RID3=$(rule_id open-rule); RID4=$(rule_id static-rule)
for r in "$RID" "$RID3" "$RID4"; do admin /api/whitelist/enable -d id="$r" -d enable=true >/dev/null; done
for r in "$RID" "$RID3"; do admin /api/whitelist/trustProxy -d id="$r" -d enable=true >/dev/null; done
admin /api/whitelist/allowLocal -d id="$RID2" -d enable=true >/dev/null
admin /api/trustedproxy/add -d ip=127.0.0.1 -d desc=e2e >/dev/null
admin /api/proxy/add -d type=host -d rootname=secret.test -d ep=127.0.0.1:19000 -d tls=false -d access="$RID" >/dev/null
admin /api/proxy/add -d type=host -d rootname=open.test -d ep=127.0.0.1:19000 -d tls=false -d access="$RID3" >/dev/null
admin /api/proxy/add -d type=host -d rootname=dyndns.test -d ep=127.0.0.1:1 -d tls=false -d tags=dyndns -d rate=true -d ratenum=10 -d bypassGlobalTLS=true >/dev/null
admin /api/plugins/groups/add -d tag=dyndns -d plugin_id=$ID >/dev/null
# Worst case for plain HTTP: the DynDNS host allows it (bypassGlobalTLS, set above). Zoraxy then still
# routes it past all plugins (handleNonTLSRequest), so the whitelist must stay untouched (T37).
restart_plugin
check "T02 first start writes a config template" "yes" "$([[ -f $PDIR/config.json ]] && echo yes || echo no)"

PW=$(openssl rand -hex 24); PW2=$(openssl rand -hex 24); PW3=$(openssl rand -hex 24)
H=$(echo "$PW" | "$PLUGIN_BIN" -hash); H2=$(echo "$PW2" | "$PLUGIN_BIN" -hash); H3=$(echo "$PW3" | "$PLUGIN_BIN" -hash)
CLIENT_FRITZ="{ \"name\": \"fritzbox\", \"password_sha256\": \"$H\", \"rules\": [\"$RID\", \"$RID3\"] }"
CLIENT_R2="{ \"name\": \"router2\", \"password_sha256\": \"$H2\", \"rules\": [\"does-not-exist\"] }"
CLIENT_GONE="{ \"name\": \"gone\", \"password_sha256\": \"$H3\", \"rules\": [\"$RID\"] }"
RULES="\"$RID2\": { \"mode\": \"warn\" }, \"$RID3\": { \"mode\": \"open_when_empty\" },
       \"$RID4\": { \"mode\": \"open_when_empty\", \"static\": [\"10.8.0.0/24\"] }"
write_config "{ \"clients\": [ $CLIENT_FRITZ, $CLIENT_R2, $CLIENT_GONE ], \"rules\": { $RULES } }"
restart_plugin
contains "T03 config validated at startup" "Config validated against" "$(plugin_log)"
contains "T04 unknown rule id is reported" "references unknown access rule 'does-not-exist'" "$(plugin_log)"

# --- modes before any report -----------------------------------------------------------------------
check "T41 static entry present after startup" "10.8.0.0/24=dyndns:static" "$(wl "$RID4")"
check "T47 static entry keeps open_when_empty rule closed" "true" "$(enabled "$RID4")"
check "T46a open_when_empty opens while empty" "false" "$(enabled "$RID3")"
check "T46b open rule reachable from anywhere while open" "200" "$(host open.test 93.184.215.14)"
check "T43 warn mode leaves the switch off" "false" "$(enabled "$RID2")"
contains "T43b warn mode logs a warning" "mode warn" "$(plugin_log)"
contains "T48 local-bypass warning in log" "allow local and loopback" "$(plugin_log)"

# --- authentication -------------------------------------------------------------------------------
check "T05 protected host blocked before any update" "403" "$(secret 87.176.150.207)"
check "T06 no credentials" "badauth 401" "$(curl "${C[@]}" "${R[@]}" -w ' %{http_code}' 'https://dyndns.test:18080/nic/update?myip=87.176.150.207')"
contains "T07 401 carries a Basic challenge" "www-authenticate: basic" "$(curl "${C[@]}" "${R[@]}" -D - -o /dev/null 'https://dyndns.test:18080/nic/update' | tr 'A-Z' 'a-z')"
check "T08 wrong password" "badauth 401" "$(upd fritzbox:wrong '?myip=87.176.150.207')"
check "T09 unknown user" "badauth 401" "$(upd "mallory:$PW" '?myip=87.176.150.207')"
check "T10 whitelist still empty" "" "$(wl)"
o=$(curl "${C[@]}" "${R[@]}" -w ' %{http_code}' "http://fritzbox:$PW@dyndns.test/nic/update?myip=87.176.150.207")
check "T37 plain HTTP never reaches the plugin (Zoraxy bypasses plugins without TLS)" "no" "$([[ $o == good* ]] && echo yes || echo no)"
check "T37b rejected HTTP request changed nothing" "" "$(wl)"

# --- update cycle ----------------------------------------------------------------------------------
check "T11 first update" "good 87.176.150.207 200" "$(upd "fritzbox:$PW" '?hostname=home&myip=87.176.150.207')"
check "T12 entry is tagged" "87.176.150.207/32=dyndns:fritzbox" "$(wl)"
check "T13 home IP now allowed" "200" "$(secret 87.176.150.207)"
check "T14 other IP still blocked" "403" "$(secret 93.184.215.14)"
check "T46c open_when_empty closes once the client reported" "true" "$(enabled "$RID3")"
check "T46d closed open rule blocks other IPs" "403" "$(host open.test 93.184.215.14)"
check "T15 same IP again" "nochg 87.176.150.207 200" "$(upd "fritzbox:$PW" '?myip=87.176.150.207')"
admin /api/whitelist/ip/add -d id="$RID" -d ip=1.1.1.1 -d comment=office >/dev/null
check "T16 IP change" "good 93.184.215.14 200" "$(upd "fritzbox:$PW" '?myip=93.184.215.14')"
check "T17 old entry gone, manual entry kept" "1.1.1.1=office 93.184.215.14/32=dyndns:fritzbox" "$(wl)"
check "T18 old IP blocked" "403" "$(secret 87.176.150.207)"
check "T19 new IP allowed" "200" "$(secret 93.184.215.14)"
check "T20 manual entry still works" "200" "$(secret 1.1.1.1)"

# --- input validation (whitelist must stay unchanged) ----------------------------------------------
for bad in 192.168.178.1 100.64.1.1 127.0.0.1 192.0.2.1 evil.example.com 1.2.3 2003::/16 fe80::1 fd00::1 2001:db8::1 \
           93.184.215.14,87.176.150.207 '%zz'; do
  out=$(upd "fritzbox:$PW" "?myip=$bad"); check "T21 rejects '$bad'" "400" "${out##* }"
done
out=$(upd "fritzbox:$PW" "?myip=192.168.1.1&password=$PW"); check "T21b password in the query of a rejected update" "400" "${out##* }"
out=$(upd "fritzbox:$PW" "?myip=93.184.215.14&myipv4=87.176.150.207"); check "T40 same family in two parameters is rejected" "400" "${out##* }"
check "T22 whitelist unchanged after bad input" "1.1.1.1=office 93.184.215.14/32=dyndns:fritzbox" "$(wl)"
check "T23 without myip: source IP 127.0.0.1 is rejected" "400" "$(o=$(upd "fritzbox:$PW" ''); echo "${o##* }")"

# --- dual stack and parameter aliases --------------------------------------------------------------
check "T24 IPv4 + LAN prefix" "good 93.184.215.14,2003:d5:df4a:a500::/56 200" "$(upd "fritzbox:$PW" '?myip=93.184.215.14,2003:d5:df4a:a500::/56')"
check "T25 host bits are masked" "nochg 93.184.215.14,2003:d5:df4a:a500::/56 200" "$(upd "fritzbox:$PW" '?myip=93.184.215.14,2003:d5:df4a:a5ff:1::/56')"
check "T26 device inside the prefix allowed" "200" "$(secret 2003:d5:df4a:a5ab::1234)"
check "T27 IPv6 outside the prefix blocked" "403" "$(secret 2003:d5:dfff::1)"
check "T28 IPv4-mapped IPv6 is treated as IPv4" "nochg 93.184.215.14,2003:d5:df4a:a500::/56 200" "$(upd "fritzbox:$PW" '?myip=::ffff:93.184.215.14,2003:d5:df4a:a500::/56')"
check "T38 myipv4/myipv6 aliases" "nochg 93.184.215.14,2003:d5:df4a:a500::/56 200" "$(upd "fritzbox:$PW" '?myipv4=93.184.215.14&myipv6=2003:d5:df4a:a500::/56')"
check "T39 ip/ipv6 aliases" "nochg 93.184.215.14,2003:d5:df4a:a500::/56 200" "$(upd "fritzbox:$PW" '?ip=93.184.215.14&ipv6=2003:d5:df4a:a500::/56')"

# --- errors, isolation, concurrency ----------------------------------------------------------------
check "T29 client with unknown rule gets 911" "911 500" "$(upd "router2:$PW2" '?myip=87.176.150.207')"
check "T30 other paths on the DynDNS host do not reach a backend" "no" "$(c=$(curl "${C[@]}" "${R[@]}" -o /dev/null -w '%{http_code}' https://dyndns.test:18080/); [[ $c == 2* ]] && echo yes || echo no)"
contains "T31 status page via Zoraxy admin" "fritzbox" "$(admin /plugin.ui/$ID/)"
PIDS=(); for ip in 5.6.7.8 9.9.9.9 8.8.4.4 5.6.7.8 87.176.150.207; do upd "fritzbox:$PW" "?myip=$ip" >/dev/null & PIDS+=($!); done; wait "${PIDS[@]}"
check "T32 five parallel updates leave exactly one IPv4 entry" "1" "$(wl | tr ' ' '\n' | grep -c '/32=dyndns:fritzbox')"
sleep 2
codes=$(for _ in $(seq 40); do curl "${C[@]}" "${R[@]}" -o /dev/null -w '%{http_code}\n' "https://fritzbox:wrong@dyndns.test:18080/nic/update"; done | sort | uniq -c)
contains "T33 Zoraxy rate limit stops guessing (429)" "429" "$codes"
sleep 2

# --- reconcile loop --------------------------------------------------------------------------------
admin /api/whitelist/enable -d id="$RID" -d enable=false >/dev/null
check "T42a admin switched enforce rule off" "false" "$(enabled "$RID")"
for _ in $(seq 70); do [[ $(enabled "$RID") == true ]] && break; sleep 1; done
check "T42 enforce re-enables the whitelist within one cycle" "true" "$(enabled "$RID")"

# --- config changes --------------------------------------------------------------------------------
check "T44a client 'gone' reports" "good 5.5.5.5 200" "$(upd "gone:$PW3" '?myip=5.5.5.5')"
contains "T44b its entry is present" "5.5.5.5/32=dyndns:gone" "$(wl)"
admin /api/whitelist/ip/add -d id="$RID3" -d ip=2.2.2.2 -d comment=manual3 >/dev/null
CLIENT_FRITZ_R1="{ \"name\": \"fritzbox\", \"password_sha256\": \"$H\", \"rules\": [\"$RID\"] }"
write_config "{ \"clients\": [ $CLIENT_FRITZ_R1, $CLIENT_R2 ], \"rules\": { \"$RID2\": { \"mode\": \"warn\" }, \"$RID4\": { \"mode\": \"open_when_empty\", \"static\": [\"10.8.0.0/24\"] } } }"
restart_plugin
check "T44 removed client's entry disappears" "no" "$(wl | grep -q 'dyndns:gone' && echo yes || echo no)"
check "T45 rule removed from config: owned entries removed, manual kept" "2.2.2.2=manual3" "$(wl "$RID3")"
check "T45b rule removed from config: switch untouched" "true" "$(enabled "$RID3")"

# --- restart ---------------------------------------------------------------------------------------
BEFORE=$(wl); kill $ZPID; wait $ZPID 2>/dev/null; start_zoraxy; sleep 5
check "T34 whitelist survives a Zoraxy restart" "$BEFORE" "$(wl)"
IP4=$(wl | tr ' ' '\n' | grep '/32=dyndns:fritzbox' | cut -d/ -f1)
contains "T49 status page shows the last report after restart" "$IP4" "$(admin /plugin.ui/$ID/)"
# T32 sent IPv4 only, so the IPv6 entry is gone by design (the last report is the desired state)
check "T35 update after restart" "nochg $IP4 200" "$(upd "fritzbox:$PW" "?myip=$IP4")"

# --- shutdown --------------------------------------------------------------------------------------
admin /api/plugins/disable -d plugin_id=$ID >/dev/null
for _ in $(seq 10); do pgrep -f "$PDIR/zoraxy-dyndns -configure" >/dev/null || break; sleep 0.5; done
check "T50 /term stops the plugin" "stopped" "$(pgrep -f "$PDIR/zoraxy-dyndns -configure" >/dev/null && echo running || echo stopped)"
contains "T50b plugin logged a clean shutdown" "Shutting down" "$(plugin_log)"
check "T50c state.json is valid JSON with the last report" "$IP4/32" "$(python3 -c "import json; print(json.load(open('$PDIR/state.json'))['clients']['fritzbox']['addresses'][0])")"

# --- secrets ---------------------------------------------------------------------------------------
check "T36 passwords never appear in logs or plugin files" "0" "$(cat "$WORK"/zoraxy.log "$WORK"/log/* "$PDIR"/*.json 2>/dev/null | grep -c -e "$PW" -e "$PW2" -e "$PW3")"

echo; echo "$PASS passed, $FAIL failed"; [[ $FAIL -eq 0 ]]
