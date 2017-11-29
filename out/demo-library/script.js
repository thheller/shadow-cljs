require("source-map-support").install();

var x = require("./lib");
var result = x.hello();
console.log("hello result", result);
