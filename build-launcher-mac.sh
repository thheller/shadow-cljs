#!/bin/sh

set -e

./build-launcher.sh

cd packages/launcher; yarn dist-mac
