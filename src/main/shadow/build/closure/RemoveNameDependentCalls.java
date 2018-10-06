package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.rhino.Node;

import java.util.Set;

import static com.google.javascript.jscomp.CustomPassExecutionTime.BEFORE_OPTIMIZATION_LOOP;

/**
 * Created by zilence on 30/03/2017.
 */
public class RemoveNameDependentCalls implements CompilerPass, NodeTraversal.Callback {
    private final AbstractCompiler compiler;
    private final Set<String> callsToRemove;

    public static void install(AbstractCompiler cc, CompilerOptions co, Set<String> names) {
        co.addCustomPass(BEFORE_OPTIMIZATION_LOOP, new RemoveNameDependentCalls(cc, names));
    }

    public RemoveNameDependentCalls(AbstractCompiler compiler, Set<String> callsToRemove) {
        this.compiler = compiler;
        this.callsToRemove = callsToRemove;
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(compiler, root, this);
    }

    @Override
    public boolean shouldTraverse(NodeTraversal nodeTraversal, Node n, Node parent) {
        switch (n.getToken()) {
            case ROOT:
            case BLOCK:
            case SCRIPT:
            case EXPR_RESULT:
                return true;

            default:
                return false;
        }
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
        if (n.isExprResult()) {
            Node call = n.getFirstChild();
            if (call.isCall()) {
                Node name = call.getFirstChild();
                if (name.isName() && callsToRemove.contains(name.getOriginalName())) {
                    parent.removeChild(n);
                    ShadowAccess.reportChangeToEnclosingScope(compiler, parent);
                }
            }
        }
    }
}
