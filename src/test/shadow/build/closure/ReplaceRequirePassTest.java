package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.Node;

import java.util.HashMap;
import java.util.Map;

public class ReplaceRequirePassTest {

    public static Node process(Compiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        // JsAst.ParseResult result = (JsAst.ParseResult) node.getProp(Node.PARSE_RESULTS);

        Map<String,Object> nested = new HashMap<>();
        nested.put("test", "module$test");

        Map<String, Map<String, Object>> outer = new HashMap<>();
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

        SourceFile srcFile = SourceFile.fromCode("test.js", "require('test'); require('goog:goog.string');");

        System.out.println(cc.toSource(process(cc, srcFile)));
    }
}
