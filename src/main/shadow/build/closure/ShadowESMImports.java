package shadow.build.closure;

import clojure.lang.RT;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.StaticSourceFile;
import com.google.javascript.rhino.Token;

import java.util.*;

public class ShadowESMImports extends NodeTraversal.AbstractPostOrderCallback  {
    private final Map<String, Set<String>> references = new HashMap<>();

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        // record any usage of any esm_import$ anywhere
        if (node.isName() && node.getString().startsWith("esm_import$")) {
            Set<String> s = references.computeIfAbsent(t.getChunk().getName(), k -> new HashSet<>());
            s.add(node.getString());
        }
    }

    public static void process(Compiler compiler, Map<String, String> imports) {
        // jsRoot not accessible otherwise, root has externs as first child and js root as second
        ShadowESMImports pass = new ShadowESMImports();
        NodeTraversal.traverse(compiler, compiler.getRoot().getSecondChild(), pass);


        for (JSChunk chunk : compiler.getModules()) {
            if (chunk.getInputCount() > 0) {
                CompilerInput firstInput = chunk.getInputs().get(0);

                Node root = firstInput.getAstRoot(compiler);

                Set<String> refs = pass.references.get(chunk.getName());
                if (refs != null) {
                    for (String ref : refs) {
                        String importVal = imports.get(ref);
                        if (importVal == null) {
                            throw new IllegalStateException("import unknown: " + ref);
                        }

                        root.addChildToFront(IR.importNode(IR.empty(), IR.importStar(ref), IR.string(importVal)));
                    }
                }
            }
        }
    }


    public static void main(String... args) {
        Compiler cc = new Compiler();


        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        co.setPrettyPrint(true);
        co.setChunkOutputType(CompilerOptions.ChunkOutputType.ES_MODULES);
        co.setEmitUseStrict(false);
        co.setLanguageIn(CompilerOptions.LanguageMode.UNSTABLE);
        co.setLanguageOut(CompilerOptions.LanguageMode.ECMASCRIPT_2021);
        CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(co);

        SourceFile testFile = SourceFile.fromCode( "test.js", "esm_import$foo(); console.log(esm_import$bar); var x = esm_import$baz.x; console.log(x);");

        cc.initOptions(co);

        Result result = cc.compile(SourceFile.fromCode("extern.js", "var esm_import$foo; var esm_import$bar; var esm_import$baz; var console;"), testFile, co);

        ShadowESMImports.process(cc, (Map<String, String>) RT.map("esm_import$foo", "foo", "esm_import$bar", "bar", "esm_import$baz", "baz"));

        System.out.println(cc.toSource());
    }
}
