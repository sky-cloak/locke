# Mirrors the same layout as the original 999.0.0-redis image: alpine + temurin 21 jre.
FROM eclipse-temurin:21-jre-alpine

ENV KC_RUN_IN_CONTAINER=true \
    LANG=en_US.UTF-8

RUN apk add --no-cache wget bash tar && \
    addgroup -g 1000 keycloak && \
    adduser -D -u 1000 -G keycloak keycloak

COPY keycloak-999.0.0-SNAPSHOT.tar.gz /tmp/

RUN tar -xzf /tmp/keycloak-999.0.0-SNAPSHOT.tar.gz -C /opt/ && \
    mv /opt/keycloak-999.0.0-SNAPSHOT /opt/keycloak && \
    rm /tmp/keycloak-999.0.0-SNAPSHOT.tar.gz && \
    chown -R keycloak:keycloak /opt/keycloak && \
    chmod -R g+rwX /opt/keycloak

USER keycloak
WORKDIR /opt/keycloak
EXPOSE 8080 8443 9000

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
