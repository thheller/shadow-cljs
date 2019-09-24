goog.module('demo.googClass');
goog.module.declareLegacyNamespace();

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

exports.Foo = Foo;