const path = require("path");
const fs = require("fs");

var jsDir = path.resolve(__dirname, 'target', 'shadow-cljs', 'self', 'ui', 'js');

var manifest = require(path.resolve(jsDir, "manifest.json"));

var bundle = fs.readFileSync("src/dev/ui-bundle.js");

manifest.forEach(function(mod) {
  mod["js-modules"].forEach(function(req) {
    bundle += ("x[\"" + req + "\"] = require(\"" + req + "\");\n");
  });
});

fs.writeFileSync("target/webpack.js", bundle);

module.exports = {
  entry: "./target/webpack.js",
  output: {
    path: jsDir,
    filename: 'bundle.js'
  }
};