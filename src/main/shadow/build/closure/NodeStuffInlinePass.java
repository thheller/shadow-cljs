package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

import java.io.File;
import java.nio.charset.Charset;

public class NodeStuffInlinePass extends NodeTraversal.AbstractPostOrderCallback implements CompilerPass {
    private final Compiler compiler;

    public NodeStuffInlinePass(Compiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        if (node.isName()) {
            // replace a few more node constants.
            // these rarely occur in npm packages and webpack inlines them like this as well
            switch (node.getString()) {
                case "__filename":
                    node.replaceWith(IR.string("/" + t.getSourceName()));
                    break;
                case "__dirname":
                    node.replaceWith(IR.string("/"));
                    break;
                case "Buffer":
                    if (t.getScope().getVar("Buffer") == null) {
                        node.replaceWith(IR.getprop(IR.name("shadow$shims"), "Buffer"));
                    }
                    break;

                default:
                    break;
            }
        }
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(compiler, root, this);
    }

    public static Node process(Compiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        JsAst.ParseResult result = (JsAst.ParseResult) node.getProp(Node.PARSE_RESULTS);

        // FIXME: don't do this if result has errors?
        NodeTraversal.Callback pass = new NodeStuffInlinePass(cc);
        NodeTraversal.traverse(cc, node, pass);

        return node;
    }

    public static void main(String... args) {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        co.setPrettyPrint(true);
        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromFile("test/closure-inputs/node_env.js", Charset.forName("UTF-8"));

        System.out.println(cc.toSource(process(cc, srcFile)));
    }
}
