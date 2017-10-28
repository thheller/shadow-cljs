
exports.test = function() {
  return "hello world";
}

exports.foo = function() {
  return require("./circular-b").foo();
}