#!/usr/bin/env sh
set -e

# Consul serves HTTPS with a certificate from a private CA, which the JVM will not trust by
# default - the handshake fails and Spring Cloud Consul reports the misleading
# "config data resource ... does not exist" rather than a TLS error.
#
# Any *.pem or *.crt dropped into CONSUL_CA_DIR is trusted at start-up. Mounting the CA rather
# than baking it into the image means a CA rotation is a ConfigMap change and a restart, not an
# image rebuild.
CA_DIR=${CONSUL_CA_DIR:-/etc/ssl/consul-ca}

# The trust store is built here, used here, and dies with the container, so its password is not a
# credential and there is nothing to inject: it holds only public CA certificates, and the password
# protects the file's integrity rather than any secret. "changeit" is the JDK's own cacerts
# password, which this store is a copy of. A *key* store - a client certificate for mutual TLS -
# would be a different matter entirely, and its password would be a real secret.
TRUSTSTORE=/tmp/truststore.p12
TRUSTSTORE_PASSWORD=changeit

if [ -d "$CA_DIR" ] && [ -n "$(find "$CA_DIR" \( -name '*.pem' -o -name '*.crt' \) 2>/dev/null | head -1)" ]; then
    # Seed from the JVM's own cacerts rather than starting empty: setting javax.net.ssl.trustStore
    # REPLACES the default trust store, so a bare one would leave the application unable to verify
    # any public CA.
    cp "${JAVA_HOME}/lib/security/cacerts" "$TRUSTSTORE"

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
# exec so the JVM is PID 1 and receives SIGTERM directly - otherwise the shell holds PID 1, the
# signal never reaches the application, and the Kafka consumer is never closed cleanly.
exec /app/k-producer-boot-__app_version__/bin/k-producer "$@"
