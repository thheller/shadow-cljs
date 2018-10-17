#!/bin/sh

set -e

rm -rf packages/shadow-cljs/cli/dist/*

lein with-profiles +cljs run -m shadow.cljs.devtools.cli release cli ui build-report babel-worker