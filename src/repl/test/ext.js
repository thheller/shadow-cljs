goog.provide("test.ext");

/**
 * @constructor
 */
test.ext.Foo = function(x) {
  this.foo = x;
}

function dummy(/** test.ext.Foo */ x) {
  console.log(x.foo);
}

dummy(new test.ext.Foo("1"));
dummy(new test.ext.Foo("2"));
dummy(new test.ext.Foo("3"));


