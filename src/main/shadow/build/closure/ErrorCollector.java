package shadow.build.closure;

import clojure.lang.IDeref;
import clojure.lang.IPersistentCollection;
import clojure.lang.ITransientCollection;
import clojure.lang.PersistentVector;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.ErrorManager;
import com.google.javascript.jscomp.JSError;

/**
 * Created by zilence on 03/07/2017.
 */
public class ErrorCollector implements ErrorManager, IDeref {

    private ITransientCollection coll;

    public ErrorCollector() {
        this.coll = PersistentVector.EMPTY.asTransient();
    }

    @Override
    public void report(CheckLevel checkLevel, JSError jsError) {
        System.out.format("report: %s error: %s%n", checkLevel, jsError);
    }

    @Override
    public Object deref() {
        return coll.persistent();
    }

    @Override
    public void generateReport() {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public int getErrorCount() {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public int getWarningCount() {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public JSError[] getErrors() {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public JSError[] getWarnings() {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public void setTypedPercent(double v) {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public double getTypedPercent() {
        throw new IllegalStateException("not implemented");
    }
}
