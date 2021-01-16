#!/usr/bin/env node

// this script searches for a local install of shadow-cljs
// so it uses the version installed in the project over a globally installed version
// but the global still works standalone if no package.json exists
// so CLJS-only projects don't have to have a package.json present
// but can still use the CLI script

var fs = require("fs");
var path = require("path");

var localLib = null;

var root = process.cwd();

// replicate default node resolve which looks in parent directories as well
// so you can run it from $PROJECT/src/foo and have it pick up $PROJECT/node_modules
for (;;) {
  var test = path.resolve(root, "node_modules", "shadow-cljs", "cli", "dist.js")

  if (fs.existsSync(test)) {
    localLib = test;
    break;
  }

  var nextRoot = path.resolve(root, "..");
  if (nextRoot == root) {
    break;
  } else {
    root = nextRoot;
  }
}

var lib = null;
if (localLib != null) {
  // console.log("shadow-cljs - using project version");
  lib = require(localLib);
} else {
  // console.log("shadow-cljs - using global version")

  // this throws if not found right?
  lib = require("./dist.js");
}

if (lib == null) {
  console.log("failed to require CLI lib", localLib);
} else {
  lib.main(process.argv.slice(2));
}

