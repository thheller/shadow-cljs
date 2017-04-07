goog.provide("test.ext");
goog.require("goog.string");

document.createElement("foo");
// React.createElement("foo");

/**
 * @constructor
 */
test.ext.Dummy = function() {
};

test.ext.Dummy.prototype.foo = function() {
  return "dummy";
}

test.ext.ok = function(/** Foreign */ x) {
  return x.foo().bar().baz();
}

test.ext.fail = function(x) {
  return x.foo().bar().baz();
}



console.log("ok", test.ext.ok(window["testObject"]));
console.log("fail", test.ext.fail(window["testObject"]));
console.log("fail", test.ext.fail(new test.ext.Dummy()));

