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

# alpenflight-web client `baseUrl` substitution layer. Keycloak's
# realm-import `${VAR}` substitution doesn't cover `client.baseUrl` (URL
# validator runs before substitution and rejects the literal placeholder),
# so we substitute at image build via this build-arg — wired from
# docker-compose.yml `args:` block. The committed JSON keeps the
# `${ALPENFLIGHT_WEB_BASE_URL}` marker (check-realm-shape.sh asserts it +
# normalize-realm-export.sh re-injects on round-trip). Rotation requires
# `rebuild-keycloak.sh` — same operator workflow as KEYCLOAK_GOOGLE_*.
ARG ALPENFLIGHT_WEB_BASE_URL=http://localhost:4201/

# USER 0 so the RUN sed below can rewrite the import file. The base image's
# `/opt/keycloak/data/import/` is root-owned (created before its USER 1000),
# so the runtime UID can't sed -i (temp file write fails). Switch to root
# for the build step only; USER 1000 takes over before runtime.
USER 0

COPY realm-export.json /opt/keycloak/data/import/realm-export.json

# Fail fast if the build-arg ended up empty (compose's `${VAR:-default}`
# only fills the *unset* case — a deploy pipeline that sets ALPENFLIGHT_WEB_BASE_URL
# to "" would otherwise bake `"baseUrl": ""` into H2 silently).
RUN test -n "$ALPENFLIGHT_WEB_BASE_URL" \
      || { echo "ALPENFLIGHT_WEB_BASE_URL build-arg is empty — refusing to bake an empty baseUrl"; exit 1; } \
 && sed -i "s|\${ALPENFLIGHT_WEB_BASE_URL}|${ALPENFLIGHT_WEB_BASE_URL}|g" \
      /opt/keycloak/data/import/realm-export.json

# Back to the base image's non-root runtime UID.
USER 1000

# Bake the custom `alpenflight` theme. Bind-mount path is unreliable on
# Docker Desktop Windows hosts; edits to assets need `docker compose
# build keycloak` to take effect.
COPY themes/ /opt/keycloak/themes/
