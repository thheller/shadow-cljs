require("source-map-support").install();

var x = require("./lib");
console.log("x", x);
var result = x.hello();
console.log("hello result", result);
