# syntax=docker/dockerfile:1

FROM docker.io/library/maven:3.9.13-eclipse-temurin-21 AS backend-build
WORKDIR /build/backend
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src/ ./src/
COPY LICENSE THIRD-PARTY.txt ./
# The build label /api/health answers (a commit or release name); empty: the project version.
ARG GLAZELAB_BUILD=
# The tests run in ./build.sh and in CI, not again in the image build.
RUN mvn -B -ntp -DskipTests -Djacoco.skip=true ${GLAZELAB_BUILD:+-Dglazelab.build=$GLAZELAB_BUILD} package

FROM docker.io/library/eclipse-temurin:21-jre-jammy AS api
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 glazelab \
    && useradd --uid 10001 --gid glazelab --no-create-home glazelab \
    && mkdir -p /var/lib/glazelab/images \
    && chown -R glazelab:glazelab /var/lib/glazelab
WORKDIR /app
COPY --from=backend-build /build/backend/target/glazelab-*.jar ./glazelab.jar
ENV GLAZELAB_IMAGES=/var/lib/glazelab/images
USER glazelab
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD curl --fail --silent http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/glazelab.jar"]
