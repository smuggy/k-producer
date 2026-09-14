# eclipse-temurin, not openjdk: the official openjdk images were deprecated and their tags pulled
# from Docker Hub, so `openjdk:21-slim` no longer resolves and the build fails outright.
#
# A JRE is enough - the tar already holds a built application - and the alpine variant is a
# fraction of the size of the Ubuntu-based one. Alpine is viable here because run.sh needs only a
# shell and keytool, both present; a distroless base would be smaller still but has neither, so
# the CA import at start-up could not run.
#
# Note alpine uses musl rather than glibc. That is fine for pure-Java code, but Kafka's optional
# compression codecs (snappy, zstd, lz4) ship native binaries - verify them before enabling
# compression, which is off by default.
FROM eclipse-temurin:25-jre-alpine AS builder
COPY k-producer-boot-__app_version__.tar /tmp/app.tar

RUN mkdir /app && \
    cd /app && \
    tar -xf /tmp/app.tar

FROM eclipse-temurin:25-jre-alpine

# No APP_VERSION arg: the version is served from /actuator/info, generated at build time by the
# Gradle buildInfo() task. The __app_version__ placeholders above and in run.sh are substituted by
# the prepareDockerDir task and are unrelated to any docker build arg.
COPY --from=builder /app /app
COPY run.sh /

EXPOSE 8080

CMD ["/run.sh"]
