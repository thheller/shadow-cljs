package shadow.build.closure;

import clojure.lang.RT;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

import java.util.HashMap;
import java.util.Map;

public class ReplaceRequirePass extends NodeTraversal.AbstractPostOrderCallback {

    private final AbstractCompiler cc;
    private final Map<String, Object> replacements;

    public ReplaceRequirePass(AbstractCompiler cc, Map<String, Object> replacements) {
        this.cc = cc;
        this.replacements = replacements;
    }

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        if (NodeUtil.isCallTo(node, "require")) {
            String require = node.getSecondChild().getString();
            // might be a clj-sym or String
            Object replacement = replacements.get(require);
            if (replacement != null) {
                // replace require("something") with shadow.npm.pkgs.module$something lookup
                final Node newNode = IR.getprop( IR.name("shadow"), "npm", "pkgs", replacement.toString());

                node.replaceWith(newNode);

                cc.reportChangeToEnclosingScope(newNode);
            }
        }
    }

    public static Node process(Compiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        JsAst.ParseResult result = (JsAst.ParseResult) node.getProp(Node.PARSE_RESULTS);

        Map<String,Object> replacements = new HashMap<>();
        replacements.put("test", "module$test");

        NodeTraversal.Callback pass = new ReplaceRequirePass(cc, replacements);
        NodeTraversal.traverseEs6(cc, node, pass);

        return node;
    }

    public static void main(String... args) {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        co.setPrettyPrint(true);
        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromCode("test.js", "require('test');");

        System.out.println(cc.toSource(process(cc, srcFile)));
    }
}
