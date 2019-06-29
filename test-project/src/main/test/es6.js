import cljs from "goog:cljs.core";
console.log("cjs exports", commonjs, cstar);
export let foo = "es6/foo";
export let map = cljs.assoc(null, "a", 1);
export default "es6-default";