import cljs from "goog:cljs.core";
import { foo as foo_other } from "./esm_other.js";

export let foo = "es6/foo";
export let map = cljs.assoc(null, "a", 1);
export default "es6-default";
export let nested = { extra: { bar: 1 }};

export function other_foo() {
    return foo_other;
}