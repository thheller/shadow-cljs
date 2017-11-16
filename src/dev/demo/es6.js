import core from "goog:cljs.core";

var foo = (x = "any old string") => {
  console.log(`Printing ${x} from cljs!`);
  return core.assoc(null, 1, 2);
};

export { foo };