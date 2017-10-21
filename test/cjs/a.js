if (process.env.NODE_ENV == "development") {
  console.log(require("./b"));
}
var d = require("./d");
console.log("d", d);
module.exports = d;