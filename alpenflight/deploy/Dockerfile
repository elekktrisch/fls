# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS spec
WORKDIR /work
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :server:platform:exportOpenApiSpec --no-daemon --console=plain

FROM node:22.23.2-bookworm AS client
WORKDIR /work
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*
COPY client/package.json client/package-lock.json client/
COPY client/platform/package.json client/platform/
RUN --mount=type=cache,target=/root/.npm \
    npm install --prefix client
COPY client/ client/
COPY --from=spec /work/server/platform/build/openapi/openapi.json server/platform/build/openapi/openapi.json
RUN --mount=type=cache,target=/root/.npm \
    npm run generate:api --workspace=platform --prefix client
RUN npm run build --workspace=platform --prefix client -- --configuration production

FROM eclipse-temurin:25-jdk AS server
WORKDIR /work
COPY . .
COPY --from=client /work/client/platform/dist/platform/browser/ server/platform/src/main/resources/static/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :server:platform:bootJar --no-daemon --console=plain

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=server /work/server/platform/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
