package shadow.build.closure;


import clojure.lang.*;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.rhino.Node;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * Created by thheller on 29/06/2017.
 *
 * takes a SourceFile and extracts all require/import names
 * similar to "detective" from node
 */
public class JsInspector {

    public static final Keyword KW_STRING = RT.keyword(null, "string");
    public static final Keyword KW_OFFSET = RT.keyword(null, "offset");
    public static final Keyword KW_IMPORT = RT.keyword(null, "import");

    public static class FileInfo implements NodeTraversal.Callback {
        JsAst.ParseResult parseResult;
        final FeatureSet features;

        public FileInfo(JsAst.ParseResult parseResult, FeatureSet features) {
            this.parseResult = parseResult;
            this.features = features;
        }

        ITransientCollection googRequires = PersistentVector.EMPTY.asTransient();
        ITransientCollection googProvides = PersistentVector.EMPTY.asTransient();
        ITransientCollection requires = PersistentVector.EMPTY.asTransient();
        ITransientCollection invalidRequires = PersistentVector.EMPTY.asTransient();
        ITransientCollection imports = PersistentVector.EMPTY.asTransient();
        ITransientCollection strOffsets = PersistentVector.EMPTY.asTransient();

        boolean esm = false;
        boolean babelEsm = false;

        String googModule = null;

        @Override
        public boolean shouldTraverse(NodeTraversal t, Node node, Node parent) {
            if (node.isFunction()) {
                Node params = node.getSecondChild(); // NodeUtil.getFunctionParameters(node) does a precondition we just did
                Node param = params.getFirstChild();
                while (param != null) {
                    // do not traverse into any function that declares a require local
                    // non-minified browserify bundles might do this
                    // function(require, module, exports) {}
                    // as that is not a require we should resolves
                    if (param.isName() && param.getString().equals("require")) {
                        return false;
                    }
                    param = param.getNext();
                }
            }
            return true;
        }

        public void recordStrOffset(Node x, boolean isImport) {
            strOffsets = strOffsets.conj(
                    RT.map(
                            KW_STRING, x.getString(),
                            KW_OFFSET, x.getSourceOffset(),
                            KW_IMPORT, isImport
                    )
            );
        }

        @Override
        public void visit(NodeTraversal t, Node node, Node parent) {
            // closure treats all files that have import or export as ESM
            if (node.isImport() || node.isExport()) {
                esm = true;
            }

            if (NodeUtil.isCallTo(node, "require")) {
                Node requireString = node.getSecondChild();


                if (requireString.isString()) {
                    String require = requireString.getString();
                    requires = requires.conj(require);
                    recordStrOffset(requireString, false);
                } else {
                    invalidRequires = invalidRequires.conj(
                            RT.map(
                                    KW_LINE, node.getLineno(),
                                    KW_COLUMN, node.getCharno()
                            ));
                }
            } else if (node.isImport() || (node.isExport() && node.hasTwoChildren())) {
                Node importString = node.getLastChild();
                String from = importString.getString();
                imports = imports.conj(from);
                recordStrOffset(importString, true);
            } else if (NodeUtil.isCallTo(node, "goog.require")) {
                String x = node.getLastChild().getString();
                googRequires = googRequires.conj(x);
            } else if (NodeUtil.isCallTo(node, "goog.provide")) {
                String x = node.getLastChild().getString();
                googProvides = googProvides.conj(x);
            } else if (NodeUtil.isCallTo(node, "goog.module")) {
                googModule = node.getLastChild().getString();
            } else if (NodeUtil.isCallTo(node, "Object.defineProperty")) {
                // Object.defineProperty(exports, "__esModule", {
                //  value: true
                //});

                Node propNode = node.getChildAtIndex(2);
                if (propNode != null && propNode.isString() && propNode.getString().equals("__esModule")) {
                    Node objNode = node.getChildAtIndex(1);
                    // FIXME: check that it is defining on exports and value: true
                    babelEsm = true;
                }
            } else if (node.isAssign() && node.getChildAtIndex(1).isTrue()) {
                // exports.__esModule = true;

                String qname = node.getChildAtIndex(0).getQualifiedName();
                if ("exports.__esModule".equals(qname) || "module.exports.__esModule".equals(qname)) {
                    babelEsm = true;
                }
            } else if (node.isAssign() && node.getChildAtIndex(0).matchesQualifiedName("module.exports")) {
                // module.exports = { "default": __webpack_require__(270), __esModule: true };

                Node objectLit = node.getChildAtIndex(1);
                if (objectLit.isObjectLit()) {
                    Node key = objectLit.getFirstChild();

                    while (key != null) {
                        if (key.isStringKey() && key.getString().equals("__esModule") && key.getFirstChild().isTrue()) {
                           babelEsm = true;
                           break;
                        }
                        key = key.getNext();
                    }
                }
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
    public static final Keyword KW_ESM = RT.keyword(NS, "js-esm");
    public static final Keyword KW_BABEL_ESM = RT.keyword(NS, "js-babel-esm");
    public static final Keyword KW_COMMONJS = RT.keyword(NS, "js-commonjs");
    public static final Keyword KW_STR_OFFSETS = RT.keyword(NS, "js-str-offsets");
    public static final Keyword KW_GOOG_PROVIDES = RT.keyword(NS, "goog-provides");
    public static final Keyword KW_GOOG_REQUIRES = RT.keyword(NS, "goog-requires");
    public static final Keyword KW_GOOG_MODULE = RT.keyword(NS, "goog-module");

    public static FileInfo getFileInfo(Compiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        JsAst.ParseResult result = (JsAst.ParseResult) node.getProp(Node.PARSE_RESULTS);

        FeatureSet features = ast.getFeatures(cc);

        // FIXME: don't do this if result has errors?
        FileInfo fileInfo = new FileInfo(result, features);
        NodeTraversal.traverseEs6(cc, node, fileInfo);

        return fileInfo;
    }

    public static IPersistentMap getFileInfoMap(Compiler cc, SourceFile srcFile) {
        FileInfo fileInfo = getFileInfo(cc, srcFile);

        return asMap(fileInfo);
    }

    public static IPersistentMap asMap(FileInfo fileInfo) {
        IPersistentMap map = RT.map(
                KW_REQUIRES, fileInfo.requires.persistent(),
                KW_IMPORTS, fileInfo.imports.persistent(),
                KW_ESM, fileInfo.esm,
                KW_BABEL_ESM, fileInfo.babelEsm,
                KW_COMMONJS, (!fileInfo.esm && !fileInfo.babelEsm),
                KW_GOOG_PROVIDES, fileInfo.googProvides.persistent(),
                KW_GOOG_REQUIRES, fileInfo.googRequires.persistent(),
                KW_GOOG_MODULE, fileInfo.googModule,
                KW_INVALID_REQUIRES, fileInfo.invalidRequires.persistent(),
                KW_LANGUAGE, fileInfo.features.version(),
                KW_STR_OFFSETS, fileInfo.strOffsets.persistent()
        );

        if (fileInfo.parseResult != null) {
            map = map.assoc(KW_ERRORS, errorsAsData(fileInfo.parseResult.errors)).assoc(KW_WARNINGS, errorsAsData(fileInfo.parseResult.warnings));
        }

        return map;
    }


    public static void main(String... args) throws IOException {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromCode("foo.js", "var x = function(require) { require('DONT'); }; require('react'); require('./foo'); import 'foo'; import { x } from 'bar';");
        //System.out.println(getFileInfoMap(cc, srcFile));

        SourceFile exportFrom = SourceFile.fromCode("foo.js", "export * from './foo';");
        //System.out.println(getFileInfoMap(cc, exportFrom));

        SourceFile goog = SourceFile.fromCode("foo.js", "goog.provide('foo'); goog.require('thing');");
        System.out.println(getFileInfoMap(cc, goog));

        SourceFile jsxFile = SourceFile.fromCode(
                "jsx.js",
                "var x = require('foo'); function render() { return <div>foo</div> };"
        );

        //System.out.println(getFileInfoMap(cc, jsxFile));

        // babel es6 conversion pattern
        SourceFile esm1 = SourceFile.fromCode(
                "esm.js",
                "Object.defineProperty(exports, \"__esModule\", {\n" +
                        "  value: true\n" +
                        "});"
        );

        System.out.println(getFileInfoMap(cc, esm1));

        // other es6 conversion pattern
        SourceFile esm2 = SourceFile.fromCode( "esm2.js", "exports.__esModule = true;");

        System.out.println(getFileInfoMap(cc, esm2));

        SourceFile esm3 = SourceFile.fromCode( "esm3.js", "module.exports = { \"default\": __webpack_require__(270), __esModule: true };");
        System.out.println(getFileInfoMap(cc, esm3));

        long start = System.currentTimeMillis();
        getFileInfoMap(cc, srcFile);
        getFileInfoMap(cc, srcFile);
        getFileInfoMap(cc, srcFile);

        long runtime = System.currentTimeMillis() - start;
        //System.out.println(getFileInfoMap(cc, srcFile));

        if (false) {
            Files.walkFileTree(Paths.get("node_modules"), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().endsWith(".js")) {
                        String content = new String(Files.readAllBytes(file));

                        if (content.contains("__esModule")) {
                            SourceFile test = SourceFile.fromCode("esm.js", content);
                            FileInfo fi = getFileInfo(cc, test);

                            if (!fi.esm && !fi.babelEsm) {
                                System.out.println(file);
                                System.out.println(asMap(fi));
                            }
                        }
                    }
                    return super.visitFile(file, attrs);
                }
            });
        }

        System.out.format("runtime:%d%n", runtime);
    }
}
