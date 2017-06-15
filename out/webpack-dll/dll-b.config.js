var path = require("path");
var webpack = require("webpack");

module.exports = {
	entry: {
		b: ["./b"]
	},
	output: {
		path: path.join(__dirname, "js"),
		filename: "[name].js",
		library: "shadow$dll_[name]"
	},
	plugins: [
	  // new webpack.NamedModulesPlugin(),
	  new webpack.DllReferencePlugin({ manifest: require("./js/a-manifest.json") }),
		new webpack.DllPlugin({
			path: path.join(__dirname, "js", "[name]-manifest.json"),
			name: "shadow$dll_[name]"
		})
	]
};