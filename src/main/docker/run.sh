#!/usr/bin/env sh
set -e

# Consul serves HTTPS with a certificate from a private CA, which the JVM will not trust by
# default - the handshake fails and Spring Cloud Consul reports the misleading
# "config data resource ... does not exist" rather than a TLS error.
#
# Any *.pem dropped into CA_DIR is imported at start-up. Mounting the CA rather than baking it
# into the image means a CA rotation is a ConfigMap change and a restart, not a rebuild.
CA_DIR=${CONSUL_CA_DIR:-/etc/ssl/consul-ca}
TRUSTSTORE=${TRUSTSTORE_PATH:-/tmp/truststore.p12}
TRUSTSTORE_PASSWORD=${TRUSTSTORE_PASSWORD:-changeit}

if [ -d "$CA_DIR" ] && [ -n "$(find "$CA_DIR" -name '*.pem' -o -name '*.crt' 2>/dev/null | head -1)" ]; then
    # Seed from the JVM's own cacerts rather than starting empty: setting javax.net.ssl.trustStore
    # REPLACES the default trust store, so a bare one would leave the application unable to verify
    # any public CA.
    cp "${JAVA_HOME}/lib/security/cacerts" "$TRUSTSTORE"
    keytool -storepasswd -keystore "$TRUSTSTORE" -storepass changeit \
            -new "$TRUSTSTORE_PASSWORD" >/dev/null 2>&1 || true

    for cert in "$CA_DIR"/*.pem "$CA_DIR"/*.crt; do
        [ -f "$cert" ] || continue
        alias=$(basename "$cert" | sed 's/\.[^.]*$//')
        echo "trusting CA: $cert (alias $alias)"
        keytool -importcert -noprompt -alias "$alias" -file "$cert" \
                -keystore "$TRUSTSTORE" -storepass "$TRUSTSTORE_PASSWORD"
    done

    JAVA_OPTS="$JAVA_OPTS -Djavax.net.ssl.trustStore=$TRUSTSTORE"
    JAVA_OPTS="$JAVA_OPTS -Djavax.net.ssl.trustStorePassword=$TRUSTSTORE_PASSWORD"
    JAVA_OPTS="$JAVA_OPTS -Djavax.net.ssl.trustStoreType=PKCS12"
    export JAVA_OPTS
else
    echo "no CA certificates in $CA_DIR; using the JVM default trust store"
fi

set -x
exec /app/k-producer-boot-__app_version__/bin/k-producer "$@"
