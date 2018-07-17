var x = require("./lib/demo.npm")

var es6 = require("./lib/module$demo$es6");

var clj = require("./lib/cljs.core");

console.log(x.foo());

console.log(clj.keyword("foo"), clj.assoc(null, 1,1));

es6.someAsyncFn();
