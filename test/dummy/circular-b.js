
exports.foo = function() {
  return require("./circular-a").test();
};