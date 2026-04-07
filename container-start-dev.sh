#!/usr/bin/env bash

set -e

CACHE_DIR="/cache"
APP="shadow-cljs"

# Helper function to create symlinks from the cache volume
setup_symlink() {
  local target_path=$1
  local cache_path=$2

  # Ensure the cache directory exists
  mkdir -p "$cache_path"
  mkdir -p "$target_path"

  # If the target already exists and is exactly the symlink we want, do nothing
  if [ -L "$target_path" ] && [ "$(readlink "$target_path")" = "$cache_path" ]; then
    return
  fi

  # If a real directory is in the way from the host, remove it to enforce isolation
  if [ -d "$target_path" ] && [ ! -L "$target_path" ]; then
    rm -rf "$target_path"
  elif [ -e "$target_path" ]; then
    rm -f "$target_path"
  fi

  # Create symlink pointing the workspace/host target to the cache volume
  ln -s "$cache_path" "$target_path"
}

echo "Setting up isolated cache symlinks..."

# Initialize workspace cache directories
setup_symlink "/code/shadow-cljs/node_modules" "$CACHE_DIR/$APP/node_modules"

# Initialize system-level caches
setup_symlink "$HOME/.npm" "$CACHE_DIR/npm"
setup_symlink "$HOME/.m2" "$CACHE_DIR/m2"
setup_symlink "$HOME/.lein" "$CACHE_DIR/lein"

# Dependency change detection (npm)
NPM_HASH_FILE="$CACHE_DIR/$APP/npm_deps_hash.md5"
# Hash both package.json and lockfile if it exists to detect any dependency changes
if [ -f package-lock.json ]; then
  CURRENT_NPM_HASH=$(md5sum package.json package-lock.json | md5sum | awk '{print $1}')
else
  CURRENT_NPM_HASH=$(md5sum package.json | md5sum | awk '{print $1}')
fi

if [ ! -f "$NPM_HASH_FILE" ] || [ "$(cat "$NPM_HASH_FILE")" != "$CURRENT_NPM_HASH" ]; then
  echo "NPM dependencies changed (or first run). Installing..."
  if [ -f package-lock.json ]; then
    npm ci
  else
    npm install
  fi
  echo "$CURRENT_NPM_HASH" > "$NPM_HASH_FILE"
else
  echo "✔ NPM dependencies are up to date."
fi

echo "Running javac ..."
lein javac

# run dev server
echo "Starting ..."
exec clj -M:dev:start "$@"
