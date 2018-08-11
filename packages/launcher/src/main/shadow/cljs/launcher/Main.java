package shadow.cljs.launcher;

import clojure.lang.DynamicClassLoader;
import java.lang.reflect.Method;

/**
 * standalone launcher that first creates a clojure DynamicClassLoader
 * and then loads the actual main clojure-fn with that classloader
 *
 * this is mostly done to ensure that the java9 jdk-classloader
 * is not the default classloader used by clojure
 *
 * this is a hack and I'm not actually sure its required
 * but it seems unreliable otherwise
 */
public class Main {

    public static void main(String[] args) throws Exception {
        DynamicClassLoader dyncl = new DynamicClassLoader();

        // FIXME: I think this can fail? Maybe need to create a new Thread?
        Thread.currentThread().setContextClassLoader(dyncl);

        Class standaloneClazz = Class.forName(
                "shadow.cljs.launcher.standalone",
                true,
                dyncl);

        // FIXME: no idea which argument types (defn -main [& args]) produces
        // so just look for a main method by name
        Method[] methods = standaloneClazz.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (m.getName().equals("main")) {
                m.invoke(null, new Object[] { args });
                return;
            }
        }

        throw new IllegalStateException("couldn't find main method");
    }
}
