package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.rhino.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.google.javascript.jscomp.CustomPassExecutionTime.AFTER_OPTIMIZATION_LOOP;
import static com.google.javascript.jscomp.CustomPassExecutionTime.BEFORE_OPTIMIZATION_LOOP;

/**
 * Created by thheller on 2019-02-19.
 * <p>
 * var module$node_modules$react$index=shadow.js.require("module$node_modules$react$index", {});
 * <p>
 * will unwrap to
 * <p>
 * shadow.js.jsRequire("module$node_modules$react$index", {});
 * <p>
 * and left as
 * <p>
 * xA("module$node_modules$react$index", {});
 * <p>
 * when the assigned alias is never used in :advanced optimized code. Closure will never remove
 * the call itself since it is using global non-optimized variables and possible side-effects.
 * <p>
 * after optimizations we traverse the tree to find such unused reference and remove them.
 * after a bit of graph analysis we can completely remove all associated JS deps which
 * would typically be prepended.
 */
public class ClearUnassignedJsRequires implements CompilerPass, NodeTraversal.Callback {
    private final AbstractCompiler compiler;
    private final Set<Object> alive;
    private final Set<Object> dead;

    public ClearUnassignedJsRequires(AbstractCompiler compiler) {
        this.compiler = compiler;
        this.dead = new HashSet<>();
        this.alive = new HashSet<>();
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(compiler, root, this);
    }

    // only need to traverse the top level since we are only interested in global vars
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

    public static boolean isRequireName(Node name) {
        if (name.isName()) {
            String fqn = name.getOriginalQualifiedName();
            if ("shadow.js.require".equals(fqn) || "shadow.js.jsRequire".equals(fqn)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
        if (n.isExprResult()) {
            Node callOrAssign = n.getFirstChild();
            if (callOrAssign.isCall()) {
                // blank call. result not assigned, dead dep

                Node name = callOrAssign.getFirstChild();
                if (isRequireName(name)) {

                    Node arg = callOrAssign.getSecondChild();

                    if (arg.isString()) {
                        dead.add(arg.getString());
                    } else if (arg.isNumber()) {
                        dead.add(((Double)arg.getDouble()).longValue());
                    }

                    parent.removeChild(n);
                    ShadowAccess.reportChangeToEnclosingScope(compiler, parent);
                }
            } else if (callOrAssign.isAssign()) {
                // ASSIGN -> [NAME CALL]

                Node call = callOrAssign.getSecondChild();
                if (call != null && call.isCall()) {
                    Node name = call.getFirstChild();

                    // result assigned, dep is alive
                    if (isRequireName(name)) {
                        Node arg = call.getSecondChild();
                        if (arg.isString()) {
                            alive.add(arg.getString());
                        } else if (arg.isNumber()) {
                            alive.add(((Double)arg.getDouble()).longValue());
                        }
                    }
                }
            }
        }
    }

    public Set<Object> getRemovedDeps() {
        // shadow.js.require should only ever be called once per dep
        // just in case it is called multiple times and alive in one case
        // but dead in the other we need to ensure things are actually dead
        dead.removeAll(alive); // yuck mutable
        return dead;
    }
}
