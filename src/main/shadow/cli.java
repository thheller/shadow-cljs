package shadow;

import clojure.lang.IFn;

public class cli {

    public static IFn var(String fqn) throws Exception {
        Class clazz = Class.forName("clojure.java.api.Clojure");
        return (IFn) clazz.getDeclaredMethod("var", Object.class).invoke(null, fqn);
    }

    public static Object require(String fqn) throws Exception {
        IFn require = var("clojure.core/require");

        Class clazz = Class.forName("clojure.java.api.Clojure");
        Object sym = clazz.getDeclaredMethod("read", String.class).invoke(null, fqn);

        return require.invoke(sym);
    }

    public static void main(String[] args) throws Exception {
        // the intent of this is to get faster feedback
        // loading everything without any output takes too long
        // this way there is some visible progress at least?
        System.out.println("Loading Clojure ...");
        require("clojure.core");
        System.out.println("Loading ClojureScript ...");

        require("cljs.closure");
        System.out.println("Loading shadow-devtools");
        require("shadow.cljs.devtools.cli");

        IFn main = var("shadow.cljs.devtools.cli/cli*");

        main.invoke(args);
    }
}
