package shadow.build.closure;
// needs to be in this package because a bunch of stuff we need is package protected

import com.google.javascript.jscomp.*;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

import java.util.*;


/**
 * Created by zilence on 21/11/15.
 */
public class ReplaceCLJSConstants implements CompilerPass, NodeTraversal.Callback {

    private final AbstractCompiler compiler;
    private final Map<String, ConstantRef> constants = new HashMap<>();

    public ReplaceCLJSConstants(AbstractCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public void process(Node externs, Node node) {
        if (!constants.isEmpty()) {
            throw new IllegalStateException("can only run once");
        }

        // traverse all inputs that require cljs.core + cljs.core itself
        for (CompilerInput input : ShadowAccess.getInputsInOrder(compiler)) {
            // FIXME: this parses the inputs to get provides/requires
            if (input.getRequires().contains("cljs.core") || input.getProvides().contains("cljs.core")) {
                NodeTraversal.traverseEs6(compiler, input.getAstRoot(compiler), this);
            }
        }

        for (ConstantRef ref : constants.values()) {
            JSModule targetModule;
            if (ref.usedIn.size() == 1) {
                targetModule = ref.usedIn.iterator().next();
            } else {
                targetModule = ShadowAccess
                        .getModuleGraph(compiler)
                        .getDeepestCommonDependencyInclusive(ref.usedIn);
            }

            // System.out.format("Moving %s to %s (used in %d)\n", ref.fqn, targetModule.getName(), ref.usedIn.size());

            Node target = null;

            for (CompilerInput input : targetModule.getInputs()) {
                if (input.getName().startsWith("shadow.cljs.module.constants.")) {
                    target = input.getAstRoot(compiler);
                    break;
                }
            }

            if (target == null) {
                throw new IllegalStateException(String.format("could not find where to put constant for module %s", targetModule.getName()));
            }

            Node varNode = IR.var(IR.name(ref.name), ref.node);
            target.addChildToBack(varNode);

            ShadowAccess.reportChangeToEnclosingScope(compiler, target);
        }
    }


    public String munge(String sym) {
        // needs to replace dots as well
        return clojure.lang.Compiler.munge(sym).replaceAll("\\.", "_DOT_");
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
        return true;
    }

    public void visit(NodeTraversal t, Node n, Node parent) {
        // new cljs.core.Keyword(ns, name, fqn, hash);
        // new cljs.core.Symbol(ns, name, fqn, hash, meta);

        if (n.isNew()) {
            int childCount = n.getChildCount();

            // cljs.core.Keyword NOT new something()
            // must check isGetProp, new something['whatever']() blows up getQualifiedName()
            // getprop, ns, name, fqn, hash (keyword), 5 nodes
            // getprop, ns, name, fqn, hash, meta (symbol), 6 nodes, if meta is not null don't replace the symbol
            if (n.getFirstChild().isGetProp() && (childCount == 5 || (childCount == 6 && n.getChildAtIndex(5).isNull()))) {
                String typeName = n.getFirstChild().getQualifiedName();

                if (typeName.equals("cljs.core.Keyword") || typeName.equals("cljs.core.Symbol")) {
                    final Node nsNode = n.getChildAtIndex(1);
                    final Node nameNode = n.getChildAtIndex(2);
                    final Node fqnNode = n.getChildAtIndex(3);
                    final Node hashNode = n.getChildAtIndex(4);

                    if ((nsNode.isString() || nsNode.isNull()) // ns may be null
                            && nameNode.isString() // name is never null
                            && fqnNode.isString() // fqn is never null
                            && hashNode.isNumber()) { // hash is precomputed

                        String fqn = fqnNode.getString();
                        String constantName = "cljs$cst$" + typeName.substring(typeName.lastIndexOf(".") + 1).toLowerCase() + "$" + munge(fqn);

                        ConstantRef ref = constants.get(constantName);
                        if (ref == null) {
                            ref = new ConstantRef(constantName, fqn, n);
                            constants.put(constantName, ref);
                        }
                        ref.usedIn.add(t.getModule());
                        // new versions use a bitset
                        ref.usedInBits.set(t.getModule().getIndex());

                        parent.replaceChild(n, IR.name(constantName));
                        ShadowAccess.reportChangeToEnclosingScope(compiler, parent);
                    }
                }
            }
        }
    }

    public class ConstantRef {
        final String name;
        final String fqn;
        final Node node;
        Set<JSModule> usedIn;
        BitSet usedInBits;

        public ConstantRef(String name, String fqn, Node node) {
            this.name = name;
            this.fqn = fqn;
            this.node = node;
            this.usedIn = new HashSet<>();
            this.usedInBits = new BitSet();
        }
    }
}
