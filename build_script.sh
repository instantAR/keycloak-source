#!/bin/bash

sudo apt update
sudo apt install docker.io

docker --version
docker-compose --version
# docker login

# create a custom network
docker network create custom_network

docker login -u jituhooda -p e0880390-5373-4ed3-bdde-58f7b5998a13

# Set your registry and image names
REGISTRY="jituhooda/keycloak-source"

# Build with cache
DOCKER_BUILDKIT=0  docker build \
    --network custom_network \
    -t "jituhooda/keycloak-source:24" \
    .
#    --cache-to type=registry,ref="$REGISTRY/buildcache,mode=max" \
#    --cache-from type=registry,ref="$REGISTRY/buildcache" \

docker push jituhooda/keycloak-source:24