#!/bin/sh

set -e

lein with-profiles +cljs run -m shadow.cljs.devtools.cli release cli create-cli ui build-report babel-worker