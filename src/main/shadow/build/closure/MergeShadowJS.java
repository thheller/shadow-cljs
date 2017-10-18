package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.Node;

import java.util.HashMap;
import java.util.Map;

public class MergeShadowJS extends NodeTraversal.AbstractPostOrderCallback implements CompilerPass {

    private final AbstractCompiler compiler;
    private final Map<String, Map<String, Object>> replacements;

    public MergeShadowJS(AbstractCompiler compiler, Map<String, Map<String, Object>> replacements) {
        this.compiler = compiler;
        this.replacements = replacements;
    }

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        if (NodeUtil.isCallTo(node, "shadow$placeholder")) {
            Node requireString = node.getSecondChild();
            if (requireString.isString()) {
                String require = requireString.getString();

                Node replacement = fileToNode(compiler, SourceFile.fromCode("dummy.js", "var x = 1;"));

                node.replaceWith(replacement);
                t.reportCodeChange();
            }
        }
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverseEs6(compiler, root, this);
    }

    public static Node fileToNode(AbstractCompiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        return ast.getAstRoot(cc);
    }

    public static Node process(Compiler cc, SourceFile srcFile) {
        Node node = fileToNode(cc, srcFile);

        Map<String,Object> nested = new HashMap<>();
        nested.put("test", "module$test");

        Map outer = new HashMap();
        outer.put("test.js", nested);

        NodeTraversal.Callback pass = new MergeShadowJS(cc, outer);
        NodeTraversal.traverseEs6(cc, node, pass);

        return node;
    }

    public static void main(String... args) {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        co.setPrettyPrint(true);
        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromCode("test.js", "shadow$placeholder('foo');");

        System.out.println(cc.toSource(process(cc, srcFile)));
    }
}
