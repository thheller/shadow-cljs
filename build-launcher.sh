#!/bin/sh

set -e

cd packages/launcher; lein uberjar && cp target/shadow-cljs-launcher*.jar ../../test-project/launcher.jar