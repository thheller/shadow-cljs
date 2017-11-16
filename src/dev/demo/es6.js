goog.require('cljs.core');

var foo = (x = "any old string") => {
  console.log(`Printing ${x} from cljs!`);
  return cljs.core.assoc(nil, 1, 2);
};

export { foo };