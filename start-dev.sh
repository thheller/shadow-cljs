#!/usr/bin/env bash

set -e

# Ensure we are in the project root
cd "$(dirname "$0")"

IMAGE_NAME="shadow-cljs-dev-env"
MARKER_FILE=".container-built"

if [ ! -f "$MARKER_FILE" ] || [ Containerfile -nt "$MARKER_FILE" ]; then
  echo "Building container image '${IMAGE_NAME}'..."
  container build \
    -t "${IMAGE_NAME}" \
    -f Containerfile \
    .
  touch "$MARKER_FILE"
fi

# the whole point of this setup is that everything is untrusted
# so mounting my local .m2 into the container defeats that purpose
# use a container volume instead

# container volume create cache

# echo "Running container '${IMAGE_NAME}'..."
# -i -t : interactive mode with tty
# --rm  : automatically clean up the container when it exits
# -v    : mount directories (workspace, .m2)
# -p    : map ports (9630 HTTP, 9620 nREPL)
# -w    : set working directory
container run \
  -it \
  --rm \
  -c 8 \
  -m 4G \
  -e SHADOW_CLJS="{:fs-watch {:impl :polling} :http {:port 9601} :nrepl {:port 9602} :cache-root \"/cache/shadow-cljs\"}" \
  -v "$PWD:/code/shadow-cljs" \
  -v "$PWD/../shadow-grove:/code/shadow-grove" \
  -v "$PWD/../shadow-css:/code/shadow-css" \
  -v "$PWD/../shadow:/code/shadow" \
  -v "$PWD/../shadow-cljsjs:/code/shadow-cljsjs" \
  -v "$PWD/../shadow-undertow:/code/shadow-undertow" \
  -v cache:/cache \
  -p 9601:9601 \
  -p 9602:9602 \
  -w /code/shadow-cljs \
  "${IMAGE_NAME}"

