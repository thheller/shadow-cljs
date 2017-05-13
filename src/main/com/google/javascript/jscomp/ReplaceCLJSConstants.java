package com.google.javascript.jscomp;
// needs to be in this package because a bunch of stuff we need is package protected

import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

import java.util.HashMap;
import java.util.Map;


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
        for (CompilerInput input : compiler.getInputsInOrder()) {
            // FIXME: this parses the inputs to get provides/requires
            if (input.getRequires().contains("cljs.core") || input.getProvides().contains("cljs.core")) {
                NodeTraversal.traverseEs6(compiler, input.getAstRoot(compiler), this);
            }
        }

        // FIXME: should probably group by module
        // finding the input is plenty of work that should probably only be done once
        for (ConstantRef ref : constants.values()) {
            JSModule targetModule = ref.module;

            boolean cljsCoreMod = false;
            CompilerInput targetInput = null;

            for (CompilerInput input : targetModule.getInputs()) {
                if (input.getProvides().contains("cljs.core")) {
                    cljsCoreMod = true;
                    targetInput = input;
                    break;
                }
            }

            Node varNode = IR.var(IR.name(ref.name), ref.node);
            if (!cljsCoreMod) {
                Node target = targetModule.getInputs().get(0).getAstRoot(compiler);
                target.addChildToFront(varNode);
            } else {
                Node target = targetInput.getAstRoot(compiler);
                target.addChildToBack(varNode);
            }
        }

        compiler.reportCodeChange();
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
                        ref.setModule(t.getModule());

                        parent.replaceChild(n, IR.name(constantName));
                    }
                }
            }
        }
    }

    public class ConstantRef {
        final String name;
        final String fqn;
        final Node node;
        JSModule module;

        public ConstantRef(String name, String fqn, Node node) {
            this.name = name;
            this.fqn = fqn;
            this.node = node;
            this.module = null;
        }

        public void setModule(JSModule module) {
            if (this.module == null) {
                this.module = module;
            } else if (this.module.equals(module)) {
                // same module
            } else if (compiler.getModuleGraph().dependsOn(module, this.module)) {
                // will already be declared in dependency
            } else {
                this.module = compiler.getModuleGraph().getDeepestCommonDependency(this.module, module);
                if (this.module == null) {
                    throw new IllegalStateException("failed to find common module");
                }
            }
        }
    }
}
