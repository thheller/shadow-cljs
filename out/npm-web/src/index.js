window["$CLJS"] = require("./cljs/cljs_env");

if (process.env.NODE_ENV !== "production") {
  require("./cljs/shadow.cljs.devtools.client.browser");
}

var x = require("./cljs/demo.browser");
x.start();


