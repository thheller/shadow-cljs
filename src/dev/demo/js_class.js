goog.module('demo.js_class');
goog.module.declareLegacyNamespace();

// requires polyfills
let [a, ...rest] = [1, 2, 3, 4, 5];

console.log("foo3");

class Foo {
    /**
     * @param {string=} a
     */
    constructor(a) {
        /**
         * @type {string}
         */
        this.a = a || "G7S";
    }

    /**
     * @return {string}
     */
    say() {
        return "Hello " + this.a;
    }
}

exports.a = a;

exports.Foo = Foo;