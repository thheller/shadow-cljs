package shadow.build.closure;

import clojure.lang.*;
import com.google.javascript.jscomp.*;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;


/**
 * collects names of global "var something = ...; function somethingGlobal() {}" declarations per file after compilation
 * closure creates let bar$$module$some$file
 * for export let bar = 1 in /some/file.js
 * the GlobalsAsVar pass turns it into a var (for hot reloading)
 * we need to track these since in not we need to export them to global again
 * easier than trying to change how closure rewrites es6
 */
public class GlobalVars implements NodeTraversal.Callback, CompilerPass {
    private final AbstractCompiler compiler;
    public APersistentMap fileVars = (APersistentMap) RT.map();


    public GlobalVars(AbstractCompiler compiler) {
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
        if (t.inGlobalScope()) {
            if (n.isVar() || n.isFunction()) {
                final Node fName = n.getFirstChild();
                if (fName.isName()) {
                    addEntry(n.getSourceFileName(), fName.getString());
                }
            }
        }
    }

    private void addEntry(String filename, String varName) {
        if (!"".equals(varName)) {
            PersistentHashSet fileSet = (PersistentHashSet) fileVars.valAt(filename);

            if (fileSet == null) {
                fileSet = (PersistentHashSet) RT.set(varName);
            } else {
                fileSet = (PersistentHashSet) fileSet.cons(varName);
            }

            fileVars = (APersistentMap) fileVars.assoc(filename, fileSet);
        }
    }

    public static void main(String[] args) {
        CompilerOptions co = new CompilerOptions();

        ShadowCompiler cc = new ShadowCompiler();

        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromCode(
                "test.js",
                "var thatGlobal = 1; function alsoGlobal() { var notGlobal = 2; return notGlobal };");

        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        GlobalVars pass = new GlobalVars(cc);

        NodeTraversal.traverse(cc, node, pass);

        System.out.println(pass.fileVars);
    }
}
