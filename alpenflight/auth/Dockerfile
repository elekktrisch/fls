# Custom Keycloak image baked with the alpenflight realm-export.json.
#
# Why bake instead of bind-mount: bind-mounting individual files from a
# Windows-host repo into a Linux container under Docker Desktop requires
# the operator to share the exact path in Docker Desktop settings —
# unreliable on first checkout. Baking matches the same pattern the
# pgadmin service uses (see alpenflight/ops/pgadmin/Dockerfile) and gives a
# portable image that boots the same on any host.
#
# Rebuild after editing realm-export.json:
#   docker compose -p alpenflight-dev build keycloak
#   docker compose -p alpenflight-dev up -d --force-recreate keycloak

FROM quay.io/keycloak/keycloak:26.5

# alpenflight-web client `baseUrl` substitution layer (S-173). Keycloak's
# `${env:VAR}` substitution at realm-import covers SMTP + IdP config but NOT
# `client.baseUrl` (URL validator runs before substitution and rejects the
# literal `${env:...}` text). Substitute at build time via a build-arg,
# wired from docker-compose.yml `args:` block. The committed JSON keeps the
# `${ALPENFLIGHT_WEB_BASE_URL}` marker (check-realm-shape.sh asserts it +
# normalize-realm-export.sh re-injects on round-trip). Rotation requires
# `rebuild-keycloak.sh` — same operator workflow as KEYCLOAK_GOOGLE_*.
ARG ALPENFLIGHT_WEB_BASE_URL=http://localhost:4200/

# USER 0 so the RUN sed below can rewrite the file (base image runs as
# UID 1000 by default; falling back to root just for the build step).
USER 0

COPY realm-export.json /opt/keycloak/data/import/realm-export.json

RUN sed -i "s|\${ALPENFLIGHT_WEB_BASE_URL}|${ALPENFLIGHT_WEB_BASE_URL}|g" \
      /opt/keycloak/data/import/realm-export.json

USER 1000

# Bake the custom `alpenflight` theme. Bind-mount path is unreliable on
# Docker Desktop Windows hosts; edits to assets need `docker compose
# build keycloak` to take effect.
COPY themes/ /opt/keycloak/themes/
