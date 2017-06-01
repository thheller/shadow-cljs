const path = require("path");
const fs = require("fs");

var outputDir = path.resolve(__dirname, "target", "shadow-cljs", "ui", "output", "js");

var manifest = require(path.resolve(outputDir, "manifest.json"));

var bundle = fs.readFileSync("src/dev/ui-bundle.js");

manifest.forEach(function(mod) {
  mod["js-modules"].forEach(function(req) {
    var actualRequire;
    if (req.indexOf("./") != -1) {
      actualRequire = path.resolve(outputDir, req);
    } else {
      actualRequire = req;
    }
    console.log("resolve", req, "to", actualRequire);
    
    bundle += ("x[\"" + req + "\"] = require(\"" + actualRequire + "\");\n");
  });
});

fs.writeFileSync("target/webpack.js", bundle);

module.exports = {
  entry: "./target/webpack.js",
  output: {
    path: outputDir,
    filename: "bundle.js"
  }
};