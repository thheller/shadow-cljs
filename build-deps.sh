#!/bin/sh

set -e

# CI-only
# installing deps so they can be cached

lein with-profiles +cljs deps

cd packages/shadow-cljs-jar; lein deps