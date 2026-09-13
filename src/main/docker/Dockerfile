# eclipse-temurin, not openjdk: the official openjdk images were deprecated and their tags pulled
# from Docker Hub, so `openjdk:21-slim` no longer resolves and the build fails outright.
# A JRE is enough here - the tar already holds a built application.
FROM eclipse-temurin:25-jre AS builder
COPY k-producer-boot-__app_version__.tar /tmp/app.tar

RUN mkdir /app && \
    cd /app && \
    tar -xf /tmp/app.tar

FROM eclipse-temurin:25-jre

ARG APP_VERSION="0.0.0"
ENV APP_VERSION=${APP_VERSION}

COPY --from=builder /app /app
COPY run.sh /

EXPOSE 8080

CMD ["/run.sh"]
