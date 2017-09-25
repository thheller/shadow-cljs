package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.Node;

import java.io.File;
import java.nio.charset.Charset;


/**
 * FIXME: meh, this might not be worth it.
 * just use babel/uglify so it matches what we find in npm packages
 *
 *
 module$node_modules$prop_types$factoryWithTypeCheckers.js
 47:            if (process.env.NODE_ENV !== "production" && typeof console !== "undefined") {

 module$node_modules$react$lib$checkReactTypeSpec.js
 8:  if (typeof process !== "undefined" && process.env && process.env.NODE_ENV === "test") {

 module$node_modules$react_dom$lib$ReactChildReconciler.js
 9:  if (typeof process !== "undefined" && process.env && process.env.NODE_ENV === "test") {

 module$node_modules$react_dom$lib$ReactCompositeComponent.js
 502:    if (process.env.NODE_ENV !== "production" || this._compositeType !== CompositeTypes.StatelessFunctional) {

 module$node_modules$react_dom$lib$checkReactTypeSpec.js
 8:  if (typeof process !== "undefined" && process.env && process.env.NODE_ENV === "test") {

 module$node_modules$react_dom$lib$flattenChildren.js
 6:  if (typeof process !== "undefined" && process.env && process.env.NODE_ENV === "test") {
 */

public class NodeEnvTraversal {

    public static abstract class Base implements NodeTraversal.Callback {
        private final String node_env;

        public Base(String node_env) {
            this.node_env = node_env;
        }

        public boolean isEnvLookup(Node node) {
            // there should be a simpler way to check this
            // must check if two getprops, /someregex/.test() breaks eval
            return node.isGetProp() &&
                    node.getFirstChild().isGetProp() &&
                    node.getFirstChild().getFirstChild().isName() &&
                    node.getQualifiedName().equals("process.env.NODE_ENV");
        }

        /**
         * very limited eval that only evals process.env.NODE_ENV lookups and strings
         *
         * @param node
         * @return string value for supported nodes, null otherwise
         */
        public String pseudoEval(Node node) {
            if (node.isString()) {
                return node.getString();
            } else if (isEnvLookup(node)) {
                return node_env;
            } else {
                return null;
            }
        }

        public boolean test(String condition, String lhs, String rhs) {
            switch (condition) {
                case "SHEQ":
                case "EQ":
                    return lhs.equals(rhs);
                case "NE":
                case "SHNE":
                    return !lhs.equals(rhs);
                default:
                    throw new IllegalStateException(String.format("unsupported if condition %s", condition));
            }
        }

        public abstract void processBranch(Node ifNode, Node parent, boolean conditionResult);

        @Override
        public boolean shouldTraverse(NodeTraversal t, Node node, Node parent) {
            return true;
        }

        @Override
        public void visit(NodeTraversal t, Node node, Node parent) {
            if (node.isIf() || node.isHook()) {
                Node condition = node.getFirstChild();
                if (condition.getChildCount() == 2) {
                    Node lhs = condition.getChildAtIndex(0);
                    Node rhs = condition.getChildAtIndex(1);

                    String lhsValue = pseudoEval(lhs);
                    String rhsValue = pseudoEval(rhs);

                    // only do something if both sides eval'd to strings
                    if (lhsValue != null && rhsValue != null) {
                        boolean result = test(condition.getToken().name(), lhsValue, rhsValue);
                        processBranch(node, parent, result);
                    }
                }
            }
        }
    }

    /**
     * pass that replaces the if with the proper branch
     */
    public static class Remove extends Base {
        private final AbstractCompiler cc;

        public Remove(String node_env, AbstractCompiler cc) {
            super(node_env);
            this.cc = cc;
        }

        public void processBranch(Node ifNode, Node parent, boolean conditionResult) {
            // if evaled to true, remove else branch
            // FIXME: is it safe to just replace with the BLOCK or should it remove the BLOCK node?
            // JS doesn't care right?
            if (conditionResult) {
                Node ifBranch = ifNode.getChildAtIndex(1);
                ifNode.replaceWith(ifBranch.detach());
            } else {
                Node elseBranch = ifNode.getChildAtIndex(2);
                if (elseBranch != null) {
                    ifNode.replaceWith(elseBranch.detach());
                } else {
                    parent.removeChild(ifNode);
                }
            }

            // FIXME: is this required when not actually compiling?
            // cc.reportChangeToChangeScope(parent);
        }
    }

    public static Node process(Compiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        JsAst.ParseResult result = (JsAst.ParseResult) node.getProp(Node.PARSE_RESULTS);

        // FIXME: don't do this if result has errors?
        NodeTraversal.Callback pass = new Remove("production", cc);
        NodeTraversal.traverseEs6(cc, node, pass);

        return node;
    }

    public static void main(String... args) {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        co.setPrettyPrint(true);
        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromFile(new File("test/closure-inputs/node_env.js"), Charset.forName("UTF-8"));

        System.out.println(cc.toSource(process(cc, srcFile)));
    }
}
