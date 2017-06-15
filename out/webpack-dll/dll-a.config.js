var path = require("path");
var webpack = require("webpack");

module.exports = {
	entry: ["./a"],
	output: {
		path: path.join(__dirname, "js"),
		filename: "a.js",
		library: "shadow$dll_a"
	},
	plugins: [
		new webpack.DllPlugin({
			path: path.join(__dirname, "js", "a-manifest.json"),
			name: "shadow$dll_a"
		})
	]
};