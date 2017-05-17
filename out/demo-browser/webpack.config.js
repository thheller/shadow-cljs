const path = require("path");

module.exports = {
  entry: "./bundle.js",

  output: {
    path: path.resolve(__dirname, "public", "js"),
    filename: "demo-webpack-bundle.js"
  }
};
