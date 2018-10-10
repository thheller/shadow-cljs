#!/bin/sh

set -e

lein run -m shadow.cljs.devtools.cli release cli create-cli ui build-report babel-worker