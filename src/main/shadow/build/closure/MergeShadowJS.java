package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.Node;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MergeShadowJS extends NodeTraversal.AbstractPostOrderCallback implements CompilerPass {

    private final AbstractCompiler compiler;
    private final Map<String, SourceFile> replacements;

    public MergeShadowJS(AbstractCompiler compiler, Map<String, SourceFile> replacements) {
        this.compiler = compiler;
        this.replacements = replacements;
    }

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        if (NodeUtil.isCallTo(node, "shadow$placeholder")) {
            Node requireString = node.getSecondChild();
            if (requireString.isString()) {
                String require = requireString.getString();


                SourceFile replacementFile = replacements.get(require);

                if (replacementFile == null) {
                    throw new IllegalStateException(String.format("found placeholder for %s without replacement", require));
                }

                Node replacement = fileToNode(compiler, replacementFile).getFirstChild().getFirstChild().detach();

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

    public static class Result {
        public final String js;
        public final String sourceMapJson;

        public Result(String js, String sourceMapJson) {
            this.js = js;
            this.sourceMapJson = sourceMapJson;
        }
    }

    public static Result merge(SourceFile input, Map<String, SourceFile> replacements, List<SourceFile> sourceMapSources) throws IOException {
        Compiler cc = new Compiler();
        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        co.setSourceMapOutputPath("/dev/null");
        co.setSourceMapFormat(SourceMap.Format.V3);
        co.setSourceMapIncludeSourcesContent(true);
        co.setApplyInputSourceMaps(true);
        co.setResolveSourceMapAnnotations(true);
        // co.setPrettyPrint(true);

        cc.initOptions(co);
        cc.initBasedOnOptions();

        Node inputNode = fileToNode(cc, input);

        MergeShadowJS pass = new MergeShadowJS(cc, replacements);
        NodeTraversal.traverseEs6(cc, inputNode, pass);

        SourceMap sm = cc.getSourceMap();
        sm.reset();

        for (SourceFile x: sourceMapSources) {
           sm.addSourceFile(x);
        }

        String js = ShadowAccess.nodeToJs(cc, sm, inputNode);

        StringWriter sw = new StringWriter();

        sm.appendTo(sw, input.getName());

        return new Result(js, sw.toString());
    }

    public static void main(String... args) throws IOException {

        SourceFile srcFile = SourceFile.fromCode("test.js", "a;shadow$placeholder('foo');b;");

        SourceFile replacement = SourceFile.fromFile("test/cjs/a-compiled.js");

        Map<String, SourceFile> replacements = new HashMap<>();
        replacements.put("foo", replacement);

        Result result = merge(srcFile, replacements, new ArrayList());

        System.out.println(result.js);
        System.out.println(result.sourceMapJson);
    }
}
