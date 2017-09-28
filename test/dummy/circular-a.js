
var b = require("./circular-b");

exports.test = function() {
  return "hello world";
}

exports.foo = function() {
  return b.foo();
}