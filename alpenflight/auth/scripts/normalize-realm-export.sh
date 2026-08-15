#!/usr/bin/env bash

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

DEV_ONLY_PASSWORDS_SATISFYING_THE_REALM_PASSWORD_POLICY = {
    'sysadmin': 'sysadmin-dev-2026!',
    'clubadmin1': 'clubadmin1-dev-2026!',
    'pilot1': 'pilot1-dev-2026!',
}

USER_FIELDS_THAT_CHANGE_ON_EVERY_BOOT_AND_EXPORT = ['createdTimestamp', 'notBefore']
REALM_FIELDS_THAT_CHANGE_ON_EVERY_BOOT_AND_EXPORT = ['createdTimestamp']

for u in users:
    for f in USER_FIELDS_THAT_CHANGE_ON_EVERY_BOOT_AND_EXPORT:
        u.pop(f, None)
    if u['username'] in DEV_ONLY_PASSWORDS_SATISFYING_THE_REALM_PASSWORD_POLICY:
        u['credentials'] = [{
            'type': 'password',
            'value': DEV_ONLY_PASSWORDS_SATISFYING_THE_REALM_PASSWORD_POLICY[u['username']],
            'temporary': False,
        }]
    if u['username'] in user_roles:
        u['realmRoles'] = sorted(user_roles[u['username']])

for f in REALM_FIELDS_THAT_CHANGE_ON_EVERY_BOOT_AND_EXPORT:
    partial.pop(f, None)

THEME_NAME_DROPPED_BY_PARTIAL_EXPORT_AND_ASSERTED_BY_CHECK_REALM_SHAPE = 'alpenflight'
partial['loginTheme'] = THEME_NAME_DROPPED_BY_PARTIAL_EXPORT_AND_ASSERTED_BY_CHECK_REALM_SHAPE
partial['accountTheme'] = THEME_NAME_DROPPED_BY_PARTIAL_EXPORT_AND_ASSERTED_BY_CHECK_REALM_SHAPE
partial['emailTheme'] = THEME_NAME_DROPPED_BY_PARTIAL_EXPORT_AND_ASSERTED_BY_CHECK_REALM_SHAPE

PRIVATE_KEY_FIELDS_KEYCLOAK_REGENERATES_ON_FIRST_IMPORT_REALM = ['privateKey', 'privateKeyPem']

if 'components' in partial:
    keys = partial['components'].get('org.keycloak.keys.KeyProvider', [])
    for k in keys:
        cfg = k.get('config', {})
        for f in PRIVATE_KEY_FIELDS_KEYCLOAK_REGENERATES_ON_FIRST_IMPORT_REALM:
            cfg.pop(f, None)

DEV_CLIENT_SECRETS_RESTORED_OVER_THE_EXPORTS_MASKED_PLACEHOLDER = {
    'alpenflight-proffix': 'alpenflight-proffix-dev-secret',
}

DEV_CLIENT_BASE_URL_BUILD_ARG_PLACEHOLDERS_THE_DOCKERFILE_SED_RESTORES = {
    'alpenflight-web': '${ALPENFLIGHT_WEB_BASE_URL}',
}

for c in partial.get('clients', []):
    c.pop('notBefore', None)
    if c.get('clientId') in DEV_CLIENT_BASE_URL_BUILD_ARG_PLACEHOLDERS_THE_DOCKERFILE_SED_RESTORES:
        c['baseUrl'] = DEV_CLIENT_BASE_URL_BUILD_ARG_PLACEHOLDERS_THE_DOCKERFILE_SED_RESTORES[c['clientId']]
    if c.get('publicClient') or c.get('bearerOnly'):
        c.pop('secret', None)
        continue
    if c.get('clientId') in DEV_CLIENT_SECRETS_RESTORED_OVER_THE_EXPORTS_MASKED_PLACEHOLDER:
        c['secret'] = DEV_CLIENT_SECRETS_RESTORED_OVER_THE_EXPORTS_MASKED_PLACEHOLDER[c['clientId']]

partial['users'] = sorted(users, key=lambda u: u['username'])

def deep_sort_string_only_arrays_which_keycloak_emits_unordered(obj):
    if isinstance(obj, dict):
        return {k: deep_sort_string_only_arrays_which_keycloak_emits_unordered(v) for k, v in obj.items()}
    if isinstance(obj, list):
        if obj and all(isinstance(x, str) for x in obj):
            return sorted(obj)
        return [deep_sort_string_only_arrays_which_keycloak_emits_unordered(x) for x in obj]
    return obj

print(json.dumps(deep_sort_string_only_arrays_which_keycloak_emits_unordered(partial), indent=2, sort_keys=True))
PYEOF
