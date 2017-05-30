if (process.env.NODE_ENV !== "production") {
  window["$CLJS"] = require("./cljs/cljs_env");
  require("./cljs/shadow.cljs.devtools.client.browser");
}

var x = require("./cljs/demo.browser");
x.start();


