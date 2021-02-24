#!/usr/bin/env bash

set -euo pipefail

if ! [[ -e ../shadow-experiments/README.md ]]; then
    echo "Please setup ../shadow-experiments/ first."
    echo "  cd .."
    echo "  git clone git@github.com:thheller/shadow-experiments.git"
    exit 1
fi

pushd packages/babel-worker
yarn
popd

pushd packages/create-cljs-project
yarn
popd

pushd packages/shadow-cljs
yarn
popd

pushd packages/ui
yarn
popd

./build-all.sh

mkdir -p src/ui-release/shadow/cljs/dist/
pushd packages/babel-worker
yarn build
popd

lein jar

# Deploy the jar to maven cache:
# cp -p ./target/aot/shadow-cljs-2.11.18-aot.jar ~/.m2/repository/thheller/shadow-cljs/2.11.18/
