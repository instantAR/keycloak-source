#!/bin/sh
echo "export KC_HTTP_ENABLED=true" >> ~/.bashrc
echo "export KC_HOSTNAME_URL=https://$DOMAIN$CLOUDVM_SERVER_PATH" >> ~/.bashrc
echo "export KC_HOSTNAME_ADMIN_URL=https://$DOMAIN$CLOUDVM_SERVER_PATH" >> ~/.bashrc

source ~/.bashrc && /opt/keycloak/keycloak-999.0.0-SNAPSHOT/bin/kc.sh start-dev --proxy-headers=xforwarded --hostname-strict=false