package shadow.build.closure;


import clojure.lang.*;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.parsing.ParserRunner;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Created by thheller on 29/06/2017.
 * <p>
 * takes a SourceFile and extracts all require/import names
 * similar to "detective" from node
 */
public class JsInspector {

    public static final Keyword KW_STRING = RT.keyword(null, "string");
    public static final Keyword KW_OFFSET = RT.keyword(null, "offset");
    public static final Keyword KW_IMPORT = RT.keyword(null, "import");

    public static class FileInfo implements NodeTraversal.Callback {
        final IPersistentVector errors;
        final IPersistentVector warnings;
        final FeatureSet features;

        public FileInfo(IPersistentVector errors, IPersistentVector warnings, FeatureSet features) {
            this.errors = errors;
            this.warnings = warnings;
            this.features = features;
        }

        ITransientCollection googRequires = PersistentVector.EMPTY.asTransient();
        ITransientCollection googRequireTypes = PersistentVector.EMPTY.asTransient();
        ITransientCollection googProvides = PersistentVector.EMPTY.asTransient();
        ITransientCollection requires = PersistentVector.EMPTY.asTransient();
        ITransientCollection invalidRequires = PersistentVector.EMPTY.asTransient();
        ITransientCollection imports = PersistentVector.EMPTY.asTransient();
        ITransientCollection dynamicImports = PersistentVector.EMPTY.asTransient();
        ITransientCollection strOffsets = PersistentVector.EMPTY.asTransient();

        boolean esm = false;
        boolean usesGlobalBuffer = false;
        boolean usesGlobalProcess = false;

        String googModule = null;
        boolean googModuleLegacyNamespace = false;

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
            } else if (NodeUtil.isCallTo(node, "require.ensure")) {
                return false;
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

        public static boolean isProcessEnvNode(Node node) {
            Node p = node.getParent();
            if (p != null && p.isGetProp()) {
                p = p.getParent();

                if (p != null && p.isGetProp() && "process.env.NODE_ENV".equals(p.getQualifiedName())) {
                    // special case for files that only use process.env.NODE_ENV but nothing else from process
                    // no need to add the process polyfill in those cases since process.env.NODE_ENV will be inlined later
                    return true;
                }
            }
            return false;
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
            } else if (node.getToken() == Token.DYNAMIC_IMPORT) {
                Node arg = node.getFirstChild();
                if (node.getChildCount() == 1 && arg.isStringLit()) {
                    String x = arg.getString();
                    dynamicImports = dynamicImports.conj(x);
                    recordStrOffset(arg, false);
                } else {
                   throw new IllegalArgumentException("file uses import() with unsupported arguments and cannot be processed");
                }
            } else if (NodeUtil.isCallTo(node, "goog.require")) {
                String x = node.getLastChild().getString();
                googRequires = googRequires.conj(x);
            } else if (NodeUtil.isCallTo(node, "goog.requireType")) {
                String x = node.getLastChild().getString();
                googRequireTypes = googRequireTypes.conj(x);
            } else if (NodeUtil.isCallTo(node, "goog.provide")) {
                String x = node.getLastChild().getString();
                googProvides = googProvides.conj(x);
            } else if (NodeUtil.isCallTo(node, "goog.module")) {
                googModule = node.getLastChild().getString();
            } else if (NodeUtil.isCallTo(node, "goog.module.declareLegacyNamespace")) {
                googModuleLegacyNamespace = true;
            } else if (node.isName() && node.getString().equals("Buffer") && t.getScope().getVar("Buffer") == null) {
                usesGlobalBuffer = true;
            } else if (node.isName() && node.getString().equals("process") && t.getScope().getVar("process") == null) {
                usesGlobalProcess = usesGlobalProcess || !isProcessEnvNode(node);
            }
        }
    }

    public static final Keyword KW_LINE = RT.keyword(null, "line");
    public static final Keyword KW_COLUMN = RT.keyword(null, "column");

    public static final String NS = null;
    public static final Keyword KW_INVALID_REQUIRES = RT.keyword(NS, "js-invalid-requires");
    public static final Keyword KW_REQUIRES = RT.keyword(NS, "js-requires");
    public static final Keyword KW_IMPORTS = RT.keyword(NS, "js-imports");
    public static final Keyword KW_DYNAMIC_IMPORTS = RT.keyword(NS, "js-dynamic-imports");
    public static final Keyword KW_ERRORS = RT.keyword(NS, "js-errors");
    public static final Keyword KW_WARNINGS = RT.keyword(NS, "js-warnings");
    public static final Keyword KW_LANGUAGE = RT.keyword(NS, "js-language");
    public static final Keyword KW_ESM = RT.keyword(NS, "js-esm");
    public static final Keyword KW_STR_OFFSETS = RT.keyword(NS, "js-str-offsets");
    public static final Keyword KW_GOOG_PROVIDES = RT.keyword(NS, "goog-provides");
    public static final Keyword KW_GOOG_REQUIRES = RT.keyword(NS, "goog-requires");
    public static final Keyword KW_GOOG_REQUIRE_TYPES = RT.keyword(NS, "goog-require-types");
    public static final Keyword KW_GOOG_MODULE = RT.keyword(NS, "goog-module");
    public static final Keyword KW_GOOG_MODULE_LEGACY_NAMESPACE = RT.keyword(NS, "goog-module-legacy-namespace");
    public static final Keyword KW_USES_GLOBAL_BUFFER = RT.keyword(NS, "uses-global-buffer");
    public static final Keyword KW_USES_GLOBAL_PROCESS = RT.keyword(NS, "uses-global-process");

    public static FileInfo getFileInfo(Compiler cc, SourceFile srcFile) throws IOException {
        ParserHelper result = ParserHelper.parse(cc, srcFile);

        FileInfo fileInfo = new FileInfo(result.errors, result.warnings, result.features);

        // null on errors, just skip and return map with errors. code later can decide to throw
        if (result.ast != null) {
            NodeTraversal.traverse(cc, result.ast, fileInfo);
        }

        return fileInfo;
    }

    public static IPersistentMap getFileInfoMap(Compiler cc, SourceFile srcFile) throws IOException {
        FileInfo fileInfo = getFileInfo(cc, srcFile);

        return asMap(fileInfo);
    }

    public static IPersistentMap asMap(FileInfo fileInfo) {
        IPersistentMap map = RT.map(
                KW_REQUIRES, fileInfo.requires.persistent(),
                KW_IMPORTS, fileInfo.imports.persistent(),
                KW_DYNAMIC_IMPORTS, fileInfo.dynamicImports.persistent(),
                KW_ESM, fileInfo.esm,
                KW_GOOG_PROVIDES, fileInfo.googProvides.persistent(),
                KW_GOOG_REQUIRES, fileInfo.googRequires.persistent(),
                KW_GOOG_REQUIRE_TYPES, fileInfo.googRequireTypes.persistent(),
                KW_GOOG_MODULE, fileInfo.googModule,
                KW_GOOG_MODULE_LEGACY_NAMESPACE, fileInfo.googModuleLegacyNamespace,
                KW_INVALID_REQUIRES, fileInfo.invalidRequires.persistent(),
                KW_LANGUAGE, fileInfo.features.version(),
                KW_STR_OFFSETS, fileInfo.strOffsets.persistent(),
                KW_USES_GLOBAL_BUFFER, fileInfo.usesGlobalBuffer,
                KW_USES_GLOBAL_PROCESS, fileInfo.usesGlobalProcess,
                KW_ERRORS, fileInfo.errors,
                KW_WARNINGS, fileInfo.warnings
        );

        return map;
    }


    public static void main(String... args) throws IOException {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        cc.initOptions(co);

        SourceFile srcFile = SourceFile.fromCode("foo.js", "var x = function(require) { require('DONT'); }; require('react'); require('./foo'); import 'foo'; import { x } from 'bar';");
        //System.out.println(getFileInfoMap(cc, srcFile));

        SourceFile exportFrom = SourceFile.fromCode("foo.js", "export * from './foo'; ...");
        System.out.println(getFileInfoMap(cc, exportFrom));

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
        SourceFile esm2 = SourceFile.fromCode("esm2.js", "exports.__esModule = true;");

        System.out.println(getFileInfoMap(cc, esm2));

        SourceFile esm3 = SourceFile.fromCode("esm3.js", "module.exports = { \"default\": __webpack_require__(270), __esModule: true };");
        System.out.println(getFileInfoMap(cc, esm3));

        SourceFile esm4 = SourceFile.fromCode("process.js", "process.env.NODE_ENV");
        System.out.println(getFileInfoMap(cc, esm4));

        SourceFile esm5 = SourceFile.fromCode("process.js", "process.cwd()");
        System.out.println(getFileInfoMap(cc, esm5));

        SourceFile esm6 = SourceFile.fromCode("process.js", "process.env.FOO");
        System.out.println(getFileInfoMap(cc, esm6));

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

                            System.out.println(file);
                            System.out.println(asMap(fi));
                        }
                    }
                    return super.visitFile(file, attrs);
                }
            });
        }

        System.out.format("runtime:%d%n", runtime);
    }
}
