# Custom Keycloak image baked with the alpenflight realm-export.json.
#
# Why bake instead of bind-mount: bind-mounting individual files from a
# Windows-host repo into a Linux container under Docker Desktop requires
# the operator to share the exact path in Docker Desktop settings —
# unreliable on first checkout. Baking matches the same pattern the
# pgadmin service uses (see next/ops/pgadmin/Dockerfile) and gives a
# portable image that boots the same on any host.
#
# Rebuild after editing realm-export.json:
#   docker compose -p alpenflight-dev build keycloak
#   docker compose -p alpenflight-dev up -d --force-recreate keycloak

FROM quay.io/keycloak/keycloak:26.5

COPY realm-export.json /opt/keycloak/data/import/realm-export.json
