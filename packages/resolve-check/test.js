const fs = require("fs");
const path = require("path");

const { CachedInputFileSystem, ResolverFactory } = require("enhanced-resolve");

// create a resolver
const myResolver = ResolverFactory.createResolver({
	// Typical usage will consume the `fs` + `CachedInputFileSystem`, which wraps Node.js `fs` to add caching.
	fileSystem: new CachedInputFileSystem(fs, 4000),
	extensions: [".js", ".json"],
	aliasFields: ["browser"]
	/* any other resolver options here. Options/defaults can be seen below */
});

// resolve a file with the new resolver
const context = {};
const resolveContext = {
  log: function(msg) {
    console.log("resolve-context: ", msg)
  }
};
const lookupStartPath = path.resolve("..", "..", "test-env");
const request = "browser-override/web";

myResolver.resolve({}, lookupStartPath, request, resolveContext, (
	err /*Error*/,
	filepath /*string*/
) => {
  console.log("err", err);
  console.log("path", filepath);
});

