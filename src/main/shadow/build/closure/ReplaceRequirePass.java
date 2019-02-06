package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

import java.util.*;

public class ReplaceRequirePass extends NodeTraversal.AbstractPostOrderCallback implements CompilerPass {

    private final AbstractCompiler compiler;
    private final Map<String, Map<String, Object>> replacements;

    public final List<ChangedRequire> changedRequires = new ArrayList<>();

    public ReplaceRequirePass(AbstractCompiler compiler, Map<String, Map<String, Object>> replacements) {
        this.compiler = compiler;
        this.replacements = replacements;
    }

    public static class ChangedRequire {
        public final Node requireNode;
        public final String sourceName;
        public final String require;
        public final Object replacement;

        public ChangedRequire(Node requireNode, String sourceName, String require, Object replacement) {
            this.requireNode = requireNode;
            this.sourceName = sourceName;
            this.require = require;
            this.replacement = replacement;
        }
    }

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        if (NodeUtil.isCallTo(node, "require")) {
            Node requireString = node.getSecondChild();
            // guard against things like require('buf' + 'fer');
            // I have no idea what the purpose of that is but https://github.com/indutny/bn.js does it
            // apparently it doesn't matter anyways
            if (requireString != null && requireString.isString()) {
                String require = requireString.getString();

                if (require.startsWith("goog:")) {
                    // closure names are global, these must be exported so they aren't renamed
                    String global = require.substring(5);
                    node.replaceWith(NodeUtil.newQName(compiler, "global." + global));
                } else {
                    String sfn = node.getSourceFileName();
                    if (sfn != null) {
                        Map<String, Object> requires = replacements.get(sfn);

                        if (requires != null) {
                            // might be a clj-sym or String
                            Object replacement = requires.get(require);
                            if (replacement != null) {
                                Node replacementNode = null;

                                if (replacement instanceof Long) {
                                    replacementNode = IR.number((Long) replacement);
                                } else {
                                    replacementNode = IR.string(replacement.toString());
                                }

                                requireString.replaceWith(replacementNode);

                                changedRequires.add(new ChangedRequire(node, sfn, require, replacement));

                                t.reportCodeChange();
                            }
                        }
                    }
                }
            }
        }
    }

    public static boolean isAlive(Node node) {
        Node test = node;
        while (true) {
           if (test == null) {
               return false;
           } else if (test.isRoot()) {
               break;
           }
           test = test.getParent();
        }
        return true;
    }

    // only call this after optimizations are done, otherwise everything will be alive
    public Set<Object> getAliveReplacements() {
        Set<Object> alive = new HashSet<>();

        for (ChangedRequire req : changedRequires) {
            if (isAlive(req.requireNode)) {
                alive.add(req.replacement);
            }
        }

        return alive;
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(compiler, root, this);
    }

}
