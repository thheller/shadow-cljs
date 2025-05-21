package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

import java.util.*;

public class ReplaceRequirePass extends NodeTraversal.AbstractPostOrderCallback implements CompilerPass {

    private final AbstractCompiler compiler;
    private final Map<String, Map<String, Object>> replacements;

    public ReplaceRequirePass(AbstractCompiler compiler, Map<String, Map<String, Object>> replacements) {
        this.compiler = compiler;
        this.replacements = replacements;
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
                // } else if (require.endsWith(".css")) {
                    // FIXME: are there projects using require("./some.css") that actually have backing ./some.css.js?
                    // could just remove the entire node but sometimes the return value of the require will be used
                    // so it is easier to just replace it with false
                //    node.replaceWith(IR.falseNode());
                //    t.reportCodeChange();
                } else {
                    String sfn = node.getSourceFileName();
                    if (sfn != null) {
                        Map<String, Object> requires = replacements.get(sfn);

                        if (requires != null) {
                            // might be a clj-sym or String
                            Object replacement = requires.get(require);
                            if (replacement != null) {
                                if (replacement instanceof Long) {
                                    Node replacementNode = IR.number((Long) replacement);
                                    requireString.replaceWith(replacementNode);
                                } else { // symbol or string
                                    String s = replacement.toString();
                                    if (s.startsWith("esm_import$")) {
                                        node.replaceWith(NodeUtil.newQName(compiler, s));
                                    } else {
                                        Node replacementNode = IR.string(s);
                                        requireString.replaceWith(replacementNode);
                                    }
                                }

                                t.reportCodeChange();
                            }
                        }
                    }
                }
            }
        } else if (node.getToken() == Token.DYNAMIC_IMPORT) {
            // assuming that the only way we get here is that JSInspector already threw for invalid import() args
            String require = node.getFirstChild().getString();

            String sfn = node.getSourceFileName();
            if (sfn != null) {
                Map<String, Object> requires = replacements.get(sfn);

                if (requires != null) {
                    // might be a clj-sym or String
                    Object replacement = requires.get(require);
                    if (replacement != null) {
                        Node replacementNode = null;

                        Node requireFn = IR.name("require");

                        Node getProp = IR.getprop(requireFn, "dynamic");

                        if (replacement instanceof Long) {
                            replacementNode = IR.call(getProp, IR.number((Long) replacement));
                        } else {
                            replacementNode = IR.call(getProp, IR.string(replacement.toString()));
                        }

                        // placing import("foo") with require.dynamic("foo")

                        node.replaceWith(replacementNode);

                        // for FindSurvivingRequireCalls, otherwise nil
                        // replaceWith transfers src infos to the new nodes
                        // so this must be called after that is done, otherwise it'll override wil null
                        requireFn.setOriginalName("require");

                        t.reportCodeChange();
                    }
                }
            }
        }
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(compiler, root, this);
    }
}
