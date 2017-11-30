package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

import java.io.File;
import java.nio.charset.Charset;

public class NodeEnvInlinePass extends NodeTraversal.AbstractPostOrderCallback implements CompilerPass {
    private final Compiler compiler;
    private final String nodeEnv;

    public NodeEnvInlinePass(Compiler compiler, String nodeEnv) {
        this.compiler = compiler;
        this.nodeEnv = nodeEnv;
    }

    public static boolean isEnvLookup(Node node) {
        // there should be a simpler way to check this
        // must check if two getprops, /someregex/.test() breaks eval
        return node.isGetProp() &&
                node.getFirstChild().isGetProp() &&
                node.getFirstChild().getFirstChild().isName() &&
                node.getQualifiedName().equals("process.env.NODE_ENV");
    }

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        if (isEnvLookup(node)) {
            node.replaceWith(IR.string(nodeEnv));
            t.reportCodeChange();
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

        // FIXME: don't do this if result has errors?
        NodeTraversal.Callback pass = new NodeEnvInlinePass(cc, "production");
        NodeTraversal.traverseEs6(cc, node, pass);

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
