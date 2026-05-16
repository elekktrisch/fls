# Custom pgAdmin image: same as upstream + pre-wired FLS Target Postgres
# connection so devs see the schema on first login without typing host/port.
#
# Built by docker-compose.yml's `pgadmin` service (profile: next).
FROM dpage/pgadmin4:9
USER root
COPY servers.json /pgadmin4/servers.json
RUN chown pgadmin:root /pgadmin4/servers.json && chmod 0644 /pgadmin4/servers.json
USER pgadmin
