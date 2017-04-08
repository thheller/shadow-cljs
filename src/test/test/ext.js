goog.provide("test.ext");

document.createElement("foo");

React.createElement("foo");


test.ext.foo = function(tag) {
  return React.createElement(tag);
}

console.log(test.ext.foo("foo"));
