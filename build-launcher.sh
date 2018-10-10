#!/bin/sh

set -e

rm -rf packages/launcher/web/js

lein run -m shadow.cljs.devtools.cli release laucher-main launcher-renderer
