#!/usr/bin/env bash
# alpenflight/auth/scripts/normalize-realm-export.sh
#
# Merge partial-export + users + per-user role mappings into a single
# deterministic realm-export.json. Strips volatile fields (timestamps,
# private keys), injects dev-only passwords for seed users, sorts keys.
#
# Inputs: 3 JSON files (partial realm, users array, user→roles map).
# Output: assembled realm export to stdout.
#
# Invoked by export-realm.sh; safe to call directly for diff debugging.

set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <realm-partial.json> <users.json> <user-roles.json>" >&2
  exit 2
fi

REALM_PARTIAL="$1"
USERS_JSON="$2"
USER_ROLES_JSON="$3"

python3 - "$REALM_PARTIAL" "$USERS_JSON" "$USER_ROLES_JSON" <<'PYEOF'
import json, sys

partial = json.load(open(sys.argv[1]))
users = json.load(open(sys.argv[2]))
user_roles = json.load(open(sys.argv[3]))

# Dev-only passwords baked alongside the realm. Same as the username for the
# seed users; README marks dev-only and instructs rotation at deploy. CI grep
# rejects any password not in this allow-set or any user outside this set
# carrying a hardcoded credential.
DEV_PASSWORDS = {
    'sysadmin': 'sysadmin',
    'clubadmin1': 'clubadmin1',
    'pilot1': 'pilot1',
}

# Drop fields that change on every boot/export.
VOLATILE_USER = ['createdTimestamp', 'notBefore']
VOLATILE_REALM = ['createdTimestamp']

for u in users:
    for f in VOLATILE_USER:
        u.pop(f, None)
    if u['username'] in DEV_PASSWORDS:
        u['credentials'] = [{
            'type': 'password',
            'value': DEV_PASSWORDS[u['username']],
            'temporary': False,
        }]
    if u['username'] in user_roles:
        u['realmRoles'] = sorted(user_roles[u['username']])

for f in VOLATILE_REALM:
    partial.pop(f, None)

# Strip private signing key — Keycloak regenerates on first --import-realm.
# CI guard rejects any export with privateKey / privateKeyPem present.
if 'components' in partial:
    keys = partial['components'].get('org.keycloak.keys.KeyProvider', [])
    for k in keys:
        cfg = k.get('config', {})
        cfg.pop('privateKey', None)
        cfg.pop('privateKeyPem', None)

# Per-client cleanup: drop notBefore + any auto-generated secret for clients
# that don't need one (public + bearer-only). Only the proffix machine
# client legitimately carries a (dev) secret. The REST partial-export masks
# secrets as "**********" — restore the canonical dev value so first-boot
# from this committed JSON gives a usable client_credentials grant.
DEV_CLIENT_SECRETS = {
    'alpenflight-proffix': 'alpenflight-proffix-dev-secret',
}
for c in partial.get('clients', []):
    c.pop('notBefore', None)
    if c.get('publicClient') or c.get('bearerOnly'):
        c.pop('secret', None)
        continue
    if c.get('clientId') in DEV_CLIENT_SECRETS:
        c['secret'] = DEV_CLIENT_SECRETS[c['clientId']]

# Final assembly + deterministic sort.
partial['users'] = sorted(users, key=lambda u: u['username'])

def deep_sort_string_arrays(obj):
    """Sort arrays whose elements are ALL strings — Keycloak treats these as
    sets and emits them in non-deterministic order. Leaves arrays of objects
    untouched (those carry semantic order, e.g. authentication flows)."""
    if isinstance(obj, dict):
        return {k: deep_sort_string_arrays(v) for k, v in obj.items()}
    if isinstance(obj, list):
        if obj and all(isinstance(x, str) for x in obj):
            return sorted(obj)
        return [deep_sort_string_arrays(x) for x in obj]
    return obj

print(json.dumps(deep_sort_string_arrays(partial), indent=2, sort_keys=True))
PYEOF
