import core from "goog:cljs.core";
import { bar } from "./more-es6.js";

var foo = (x = "any old string") => {
  console.log(`Printing ${x} from cljs!`);
  console.log(core.assoc(null, 1, 2));
  return bar(x);
};

async function someAsyncFn(thing)  {
  var value = await thing;
  console.log("await value", value);
}

export class Thing {
  constructor() {
    console.log("hello world");
  }
}

export { foo, someAsyncFn };
export default "defaultExport";