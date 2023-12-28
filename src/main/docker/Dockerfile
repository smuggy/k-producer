FROM openjdk:21-slim as builder
COPY k-producer-boot-__app_version__.tar /tmp/app.tar

RUN mkdir /app && \
    cd /app && \
    tar -xf /tmp/app.tar

FROM openjdk:21-slim

ARG APP_VERSION="0.0.0"
ENV APP_VERSION=${APP_VERSION}

COPY --from=builder /app /app
COPY run.sh /

EXPOSE 8080

CMD ["/run.sh"]
