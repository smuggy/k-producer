FROM openjdk:17-slim as builder
COPY k-producer-boot-__app_version__.tar /tmp/app.tar

RUN mkdir /app && \
    cd /app && \
    tar -xf /tmp/app.tar

FROM openjdk:17-slim

COPY --from=builder /app /app
COPY run.sh /

EXPOSE 8080

CMD ["/run.sh"]
