package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;


/**
 * util pass to transform ESM rewritten export const foo from const to var
 * since with ES6+ it'll remain as const but fail eval-based loading since const scope
 * is too strict.
 */
public class GlobalsAsVar implements NodeTraversal.Callback, CompilerPass {
    private final AbstractCompiler compiler;

    public GlobalsAsVar(AbstractCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(compiler, root, this);
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
        return t.inGlobalScope();
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
        if (n.isLet() || n.isConst()) {
            if (!parent.isExport()) {
                Node lhs = n.getFirstChild();
                Node rhs = lhs.getFirstChild();
                Node replacement;
                if (rhs != null) {
                    replacement = IR.var(lhs.detach(), rhs.detach());
                } else {
                    replacement = IR.var(lhs.detach());
                }
                n.replaceWith(replacement);
            }
        }
    }

    public static void main(String[] args) {
        CompilerOptions co = new CompilerOptions();

        ShadowCompiler cc = new ShadowCompiler();

        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromCode("test.js", "let noInit; const foo = 1; function foo() { const bar = 2; }");

        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        GlobalsAsVar pass = new GlobalsAsVar(cc);

        NodeTraversal.traverse(cc, node, pass);

        System.out.println(cc.toSource(node));
    }
}
