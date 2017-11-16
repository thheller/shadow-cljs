import core from "goog:cljs.core";

import { createElement } from "react";

import { bar } from "./more-es6";

var foo = (x = "any old string") => {
  console.log(`Printing ${x} from cljs!`);
  console.log(core.assoc(null, 1, 2));
  console.log(createElement("h1", null, x));
  return bar(x);
};

export { foo };