package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.Node;

import java.util.HashMap;
import java.util.Map;

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
            String require = node.getSecondChild().getString();
            String sfn = node.getSourceFileName();
            if (sfn != null) {
                Map<String, Object> requires = replacements.get(sfn);

                if (requires != null) {
                    // might be a clj-sym or String
                    Object replacement = requires.get(require);
                    if (replacement != null) {
                        // replace require("something") with shadow.npm.pkgs.module$something lookup
                        final Node newNode = NodeUtil.newQName(compiler, replacement.toString());
                        node.replaceWith(newNode);
                        t.reportCodeChange();
                    }
                }
            }
        }
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverseEs6(compiler, root, this);
    }

    public static Node process(Compiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        JsAst.ParseResult result = (JsAst.ParseResult) node.getProp(Node.PARSE_RESULTS);

        Map<String,Object> nested = new HashMap<>();
        nested.put("test", "module$test");

        Map outer = new HashMap();
        outer.put("test.js", nested);

        NodeTraversal.Callback pass = new ReplaceRequirePass(cc, outer);
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
