#!/usr/bin/env node

console.log("process-pid", process.pid);

var lib = require("./packages/shadow-cljs/cli/dist/shadow.cljs.npm.cli.js");
lib.main(process.argv.slice(2));

