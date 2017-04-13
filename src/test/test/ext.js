goog.provide("test.ext");

document.createElement("foo");

React.createElement("foo");

/**
 * @constructor
 */
test.ext.Foo = function(x) {
  this.foo = x;
}

test.ext.dummy = function(x) {
  console.log(x.foo);
}

test.ext.dummy(new test.ext.Foo("1"));
test.ext.dummy(new test.ext.Foo("2"));
test.ext.dummy(new test.ext.Foo("3"));

