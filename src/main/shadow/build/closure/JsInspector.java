package shadow.build.closure;


import clojure.lang.*;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.rhino.Node;

import java.util.List;

/**
 * Created by thheller on 29/06/2017.
 * <p>
 * takes a SourceFile and extracts all require/import names
 * similar to "detective" from node
 * <p>
 * I have no idea why Closure doesn't have something like this
 * <p>
 * FIXME: should probably be more like JSFileParser, actually parsing might not always be possible (eg. JSX)
 */
public class JsInspector {

    public static class RequireCollector implements NodeTraversal.Callback {
        ITransientCollection requires = PersistentVector.EMPTY.asTransient();
        ITransientCollection invalidRequires = PersistentVector.EMPTY.asTransient();
        ITransientCollection imports = PersistentVector.EMPTY.asTransient();

        @Override
        public boolean shouldTraverse(NodeTraversal t, Node node, Node parent) {
            return true; // require may be anywhere
        }

        @Override
        public void visit(NodeTraversal t, Node node, Node parent) {
            if (NodeUtil.isCallTo(node, "require")) {
                Node requireString = node.getSecondChild();

                if (requireString.isString()) {
                    requires = requires.conj(requireString.getString());
                } else {
                    invalidRequires = invalidRequires.conj(
                            RT.map(
                                KW_LINE, node.getLineno(),
                                KW_COLUMN, node.getCharno()
                            ));
                }
            } else if (node.isImport()) {
                String from =  node.getLastChild().getString();
                imports = imports.conj(from);
            } else if (node.isExport() && node.hasTwoChildren()) {
                String from = node.getLastChild().getString();
                imports = imports.conj(from);
            }
        }
    }

    public static final Keyword KW_LINE = RT.keyword(null, "line");
    public static final Keyword KW_COLUMN = RT.keyword(null, "column");
    public static final Keyword KW_MESSAGE = RT.keyword(null, "message");

    public static IPersistentVector errorsAsData(List errors) {
       ITransientCollection result = PersistentVector.EMPTY.asTransient();

        for (int i = 0; i < errors.size(); i++) {
            JsAst.RhinoError error = (JsAst.RhinoError) errors.get(i);

            Object data = RT.map(
                    KW_LINE, error.line,
                    KW_COLUMN, error.lineOffset,
                    KW_MESSAGE, error.message
            );

            result = result.conj(data);
        }

       return (IPersistentVector) result.persistent();
    }

    public static final String NS = null;
    public static final Keyword KW_INVALID_REQUIRES = RT.keyword(NS, "js-invalid-requires");
    public static final Keyword KW_REQUIRES = RT.keyword(NS, "js-requires");
    public static final Keyword KW_IMPORTS = RT.keyword(NS, "js-imports");
    public static final Keyword KW_ERRORS = RT.keyword(NS, "js-errors");
    public static final Keyword KW_WARNINGS = RT.keyword(NS, "js-warnings");
    public static final Keyword KW_LANGUAGE = RT.keyword(NS, "js-language");

    public static IPersistentMap getFileInfo(Compiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        JsAst.ParseResult result = (JsAst.ParseResult) node.getProp(Node.PARSE_RESULTS);

        FeatureSet features = ast.getFeatures(cc);

        // FIXME: don't do this if result has errors?
        RequireCollector collector = new RequireCollector();
        NodeTraversal.traverseEs6(cc, node, collector);

        IPersistentMap map = RT.map(
                KW_REQUIRES, collector.requires.persistent(),
                KW_IMPORTS, collector.imports.persistent(),
                KW_INVALID_REQUIRES, collector.invalidRequires.persistent(),
                KW_LANGUAGE, features.version()
        );

        if (result != null) {
            map = map.assoc(KW_ERRORS, errorsAsData(result.errors)).assoc(KW_WARNINGS, errorsAsData(result.warnings));
        }

        return map;
    }


    public static void main(String... args) {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromCode("foo.js", "require('react'); require('./foo'); import 'foo'; import { x } from 'bar';");


        System.out.println(getFileInfo(cc, srcFile));

        SourceFile exportFrom = SourceFile.fromCode("foo.js", "export * from './foo';");
        System.out.println(getFileInfo(cc, exportFrom));



        SourceFile jsxFile = SourceFile.fromCode(
                "jsx.js",
                "var x = require('foo'); function render() { return <div>foo</div> };"
        );

        System.out.println(getFileInfo(cc, jsxFile));


        long start = System.currentTimeMillis();
        getFileInfo(cc, srcFile);
        getFileInfo(cc, srcFile);
        getFileInfo(cc, srcFile);

        long runtime = System.currentTimeMillis() - start;
        System.out.println(getFileInfo(cc, srcFile));

        System.out.format("runtime:%d%n", runtime);
    }
}
