/*
 * Copyright 2018 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// thheller
// started from https://github.com/google/closure-compiler/blob/642bbf620cbf503a515a0cee394fab5f20727eac/src/com/google/javascript/jscomp/Es6RewriteModulesToCommonJsModules.java#L40
// but rewrote most of it since the goals are different

package com.google.javascript.jscomp;

import static clojure.lang.Compiler.munge;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import clojure.lang.RT;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

import java.util.*;

import org.jspecify.nullness.Nullable;

/**
 * Rewrites ESM import and exports to CJS require, for use as :shadow-js files
 * <p>
 * import { x, y } from "whatever";
 * use(x, y);
 * // result
 * var require$whatever = require("whatever");
 * use(require$whatever.x, require$whatever.y);
 * // data
 * importRequests.put("require$whatever", "whatever");
 * <p>
 * <p>
 * import * as z from "whatever";
 * // result
 * var z = require("whatever");
 * // data
 * importRequests.put("require$whatever", "whatever");
 * <p>
 * <p>
 * import X from "whatever";
 * // result
 * var require$whatever = require("whatever");
 * var default$$require$whatever = require.esmDefault(require$whatever);
 * default$$require$whatever.default;
 * // data
 * importRequests.put("require$whatever", "whatever");
 * defaultWraps.put("default$$require$whatever", "require$whatever");
 * <p>
 * <p>
 * needs esmDefault function call for esm-cjs compat emulation
 * since module.exports = "foo" is supposed to map to the default export
 * and directly accessing "require$whatever.default" wouldn't work for the above
 * <p>
 * we must access via "alias.default" so that the "live reference" of actual ESM remains intact
 * doing var X = require.esmDefault(require$whatever); and no .default access would break that
 * I'm not sure how relevant that actually is, but I assume this is why webpack and babel do it like this
 */

public class ShadowESModuleRewriter extends AbstractPostOrderCallback {
    private static final String JSCOMP_DEFAULT_EXPORT = "$$default";
    private static final String GLOBAL = "global"; // dead, legacy
    private static final String MODULE = "module";
    private static final String EXPORTS = "exports";
    private static final String REQUIRE = "require";

    private @Nullable Node requireInsertSpot = null;

    private final AbstractCompiler compiler;
    private final Node script;

    // TreeMap because ES6 orders the export key using natural ordering.
    private final Map<String, LocalQName> exportedNameToLocalQName = new TreeMap<>();

    private final Set<Node> imports = new HashSet<>();

    private final Map<String, String> importRequests = new HashMap<>();
    private final Map<String, String> defaultWraps = new HashMap<>();

    ShadowESModuleRewriter(AbstractCompiler compiler, Node script) {
        this.compiler = compiler;
        this.script = script;
    }

    private static class LocalQName {
        final String qName;

        /**
         * Node to use for source information. All exported properties are transpiled to ES5 getters so
         * debuggers will automatically step into these, even with source maps. When stepping in this
         * node will be displayed in the source map. In general it should either be the export itself
         * (e.g. export function or class) or the specific name being exported (export specs, const,
         * etc).
         */
        final Node nodeForSourceInfo;

        LocalQName(String qName, Node nodeForSourceInfo) {
            this.qName = qName;
            this.nodeForSourceInfo = nodeForSourceInfo;
        }
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
        switch (n.getToken()) {
            case IMPORT:
                visitImport(n);
                break;
            case EXPORT:
                visitExport(t, n, parent);
                break;
            case SCRIPT:
                visitScript(t, n);
                break;
            case NAME:
                maybeRenameImportedValue(t, n);
                break;
            default:
                break;
        }
    }

    /**
     * Given an import node gets the name of the var to use for the imported module.
     *
     * <p>Example: {@code import {v} from './foo.js'; use(v);} Can become:
     *
     * <pre>
     *   const module$foo = require('./foo.js');
     *   use(module$foo.v);
     * </pre>
     * <p>
     * This method would return "module$foo".
     *
     * <p>Note that if there is a star import the name will be preserved.
     *
     * <p>Example:
     *
     * <pre>
     *   import defaultValue, * as foo from './foo.js';
     *   use(defaultValue, foo.bar);
     * </pre>
     * <p>
     * Can become:
     *
     * <pre>
     *   const foo = require('./foo.js'); use(foo.defaultValue, foo.bar);
     * </pre>
     *
     * <p>This makes debugging quite a bit easier as source maps are not great with renaming.
     */
    private String getVarNameOfImport(Node importDecl) {
        checkState(importDecl.isImport());
        if (importDecl.getSecondChild().isImportStar()) {
            return importDecl.getSecondChild().getString();
        }
        return getVarNameOfImport(importDecl.getLastChild().getString());
    }

    private String getVarNameOfImport(String importRequest) {
        return "require$" + munge(importRequest).replaceAll("\\.", "_DOT_");
    }

    /**
     * @return qualified name to use to reference an imported value.
     * <p>Examples:
     * <ul>
     *   <li>If referencing an import spec like v in "import {v} from './foo.js'" then this
     *       would return "module$foo.v".
     *   <li>If referencing an import star like m in "import * as m from './foo.js'" then this
     *       would return "m".
     *   <li>If referencing an import default like d in "import d from './foo.js'" then this
     *       would return "module$foo.default".
     *       <p>Used to rename references to imported values within this module.
     */
    private String getNameOfImportedValue(Node nameNode) {
        Node importDecl = nameNode;

        while (!importDecl.isImport()) {
            importDecl = importDecl.getParent();
        }

        String moduleName = getVarNameOfImport(importDecl);

        if (nameNode.getParent().isImportSpec()) {
            return moduleName + "." + nameNode.getParent().getFirstChild().getString();
        } else if (nameNode.isImportStar()) {
            return moduleName;
        } else {
            checkState(nameNode.getParent().isImport());
            return "default$$" + moduleName + ".default";
        }
    }

    /**
     * @param nameNode any variable name that is potentially from an import statement
     * @return qualified name to use to reference an imported value if the given node is an imported
     * name or null if the value is not imported or if it is in the import statement itself
     */
    private @Nullable String maybeGetNameOfImportedValue(Scope s, Node nameNode) {
        checkState(nameNode.isName());
        Var var = s.getVar(nameNode.getString());

        if (var != null
                // variables added implicitly to the scope, like arguments, have a null name node
                && var.getNameNode() != null
                && NodeUtil.isImportedName(var.getNameNode())
                && nameNode != var.getNameNode()) {
            return getNameOfImportedValue(var.getNameNode());
        }

        return null;
    }

    /**
     * Renames the given name node if it is an imported value.
     */
    private void maybeRenameImportedValue(NodeTraversal t, Node n) {
        checkState(n.isName());
        Node parent = n.getParent();

        if (parent.isExport()
                || parent.isExportSpec()
                || parent.isImport()
                || parent.isImportSpec()) {
            return;
        }

        String qName = maybeGetNameOfImportedValue(t.getScope(), n);

        if (qName != null) {
            n.replaceWith(NodeUtil.newQName(compiler, qName));
            t.reportCodeChange();
        }
    }

    private void visitScript(NodeTraversal t, Node script) {
        checkState(this.script == script);
        Node moduleNode = script.getFirstChild();
        checkState(moduleNode.isModuleBody());
        moduleNode.detach();
        script.addChildrenToFront(moduleNode.removeChildren());

        addRequireCalls();
        addExportDef();
    }

    /**
     * Adds one call to require per imported module.
     */
    private void addRequireCalls() {
        if (!importRequests.isEmpty()) {
            for (Node importDecl : imports) {
                importDecl.detach();
            }

            for (Map.Entry<String, String> request : importRequests.entrySet()) {
                String varName = request.getKey();
                // relying on ReplaceRequirePass to replace actual require strings with their aliases
                Node requireCall = IR.call(IR.name(REQUIRE), IR.string(request.getValue()));
                // don't know what this is, but doesn't seem to make any difference
                // requireCall.putBooleanProp(Node.FREE_CALL, true);
                Node decl = IR.var(IR.name(varName), requireCall);
                decl.srcrefTreeIfMissing(script);
                if (requireInsertSpot == null) {
                    script.addChildToFront(decl);
                } else {
                    decl.insertAfter(requireInsertSpot);
                }
                requireInsertSpot = decl;
            }

            // add wrap calls for esm-cjs interop where .default is special and supposed
            // to map to module.exports directly from CJS
            // creating a secondary var, so we don't mess with the first one
            // since import { x } from "cjs" should NOT map to alias$cjs.default.x
            // basically _interopRequireDefault from babel
            for (Map.Entry<String, String> wrap : defaultWraps.entrySet()) {
                Node wrapCall = IR.call(NodeUtil.newQName(compiler, "require.esmDefault"), IR.name(wrap.getValue()));
                Node decl = IR.var(IR.name(wrap.getKey()), wrapCall);

                // this really can't be null anymore, can it?
                if (requireInsertSpot == null) {
                    script.addChildToFront(decl);
                } else {
                    decl.insertAfter(requireInsertSpot);
                }

                requireInsertSpot = decl;
            }
        }
    }

    /**
     * Adds exports to the exports object using Object.defineProperties.
     */
    private void addExportDef() {
        if (!exportedNameToLocalQName.isEmpty()) {
            Node definePropertiesLit = IR.objectlit();

            // leave babel like __esModule marker since many already transpiled
            // sources rely on that convention
            Node objLit = IR.objectlit(
                    IR.stringKey("enumerable", IR.trueNode()),
                    IR.stringKey("value", IR.trueNode()));

            definePropertiesLit.addChildToBack(IR.stringKey("__esModule", objLit));

            for (Map.Entry<String, LocalQName> entry : exportedNameToLocalQName.entrySet()) {
                addExport(definePropertiesLit, entry.getKey(), entry.getValue());
            }

            script.addChildToFront(
                    IR.exprResult(
                                    IR.call(
                                            NodeUtil.newQName(compiler, "Object.defineProperties"),
                                            IR.name(EXPORTS),
                                            definePropertiesLit))
                            .srcrefTreeIfMissing(script));
        }
    }

    /**
     * Adds an ES5 getter to the given object literal to use an an export.
     */
    private void addExport(Node definePropertiesLit, String exportedName, LocalQName localQName) {
        Node exportedValue = NodeUtil.newQName(compiler, localQName.qName);
        Node getterFunction =
                IR.function(IR.name(""), IR.paramList(), IR.block(IR.returnNode(exportedValue)));
        getterFunction.srcrefTree(localQName.nodeForSourceInfo);

        Node objLit =
                IR.objectlit(
                        IR.stringKey("enumerable", IR.trueNode()), IR.stringKey("get", getterFunction));
        definePropertiesLit.addChildToBack(IR.stringKey(exportedName, objLit));

        compiler.reportChangeToChangeScope(getterFunction);
    }

    private void visitImport(Node importDecl) {
        imports.add(importDecl);

        // import always has 3 children
        // first is either the default export name or empty
        // second is either isImportStar(), isImportSpecs() or empty
        // third is always the requested module as stringlit

        String request = importDecl.getLastChild().getString();
        String alias = getVarNameOfImport(importDecl);

        importRequests.put(alias, request);

        if (importDecl.getFirstChild().isName()) {
            String defaultAlias = "default$$" + alias;
            defaultWraps.put(defaultAlias, alias);
        }
    }

    private void visitExportDefault(NodeTraversal t, Node export) {
        Node child = export.getFirstChild();
        String name = null;

        if (child.isFunction() || child.isClass()) {
            name = NodeUtil.getName(child);
        }

        if (name != null) {
            Node decl = child.detach();
            export.replaceWith(decl);
        } else {
            name = JSCOMP_DEFAULT_EXPORT;
            // Default exports are constant in more ways than one. Not only can they not be
            // overwritten but they also act like a const for temporal dead-zone purposes.
            Node var = IR.constNode(IR.name(name), export.removeFirstChild());
            export.replaceWith(var.srcrefTreeIfMissing(export));
            NodeUtil.addFeatureToScript(t.getCurrentScript(), Feature.CONST_DECLARATIONS, compiler);
        }

        exportedNameToLocalQName.put("default", new LocalQName(name, export));
        t.reportCodeChange();
    }

    private void visitExportFrom(NodeTraversal t, Node export, Node parent) {
        //   export {x, y as z} from 'moduleIdentifier';
        Node moduleIdentifier = export.getLastChild();
        Node importNode = IR.importNode(IR.empty(), IR.empty(), moduleIdentifier.cloneNode());
        importNode.srcref(export);
        importNode.insertBefore(export);
        visit(t, importNode, parent);

        String moduleName = getVarNameOfImport(moduleIdentifier.getString());

        for (Node exportSpec = export.getFirstFirstChild();
             exportSpec != null;
             exportSpec = exportSpec.getNext()) {
            exportedNameToLocalQName.put(
                    exportSpec.getLastChild().getString(),
                    new LocalQName(moduleName + "." + exportSpec.getFirstChild().getString(), exportSpec));
        }

        export.detach();
        t.reportCodeChange();
    }

    private void visitExportSpecs(NodeTraversal t, Node export) {
        //     export {Foo};
        for (Node exportSpec = export.getFirstFirstChild();
             exportSpec != null;
             exportSpec = exportSpec.getNext()) {
            String localName = exportSpec.getFirstChild().getString();
            Var var = t.getScope().getVar(localName);
            if (var != null && NodeUtil.isImportedName(var.getNameNode())) {
                localName = maybeGetNameOfImportedValue(t.getScope(), exportSpec.getFirstChild());
                checkNotNull(localName);
            }
            exportedNameToLocalQName.put(
                    exportSpec.getLastChild().getString(), new LocalQName(localName, exportSpec));
        }
        export.detach();
        t.reportCodeChange();
    }

    private void visitExportNameDeclaration(Node declaration) {
        //    export var Foo;
        //    export let {a, b:[c,d]} = {};
        NodeUtil.visitLhsNodesInNode(declaration, this::addExportedName);
    }

    private void addExportedName(Node lhs) {
        checkState(lhs.isName());
        String name = lhs.getString();
        exportedNameToLocalQName.put(name, new LocalQName(name, lhs));
    }

    private void visitExportDeclaration(NodeTraversal t, Node export) {
        //    export var Foo;
        //    export function Foo() {}
        // etc.
        Node declaration = export.getFirstChild();

        if (NodeUtil.isNameDeclaration(declaration)) {
            visitExportNameDeclaration(declaration);
        } else {
            checkState(declaration.isFunction() || declaration.isClass());
            String name = declaration.getFirstChild().getString();
            exportedNameToLocalQName.put(name, new LocalQName(name, export));
        }

        export.replaceWith(declaration.detach());
        t.reportCodeChange();
    }

    private void visitExportStar(NodeTraversal t, Node export, Node parent) {
        //   export * from 'moduleIdentifier';
        Node moduleIdentifier = export.getLastChild();

        // Make an "import 'spec'" from this export node and then visit it to rewrite to a require().
        Node importNode = IR.importNode(IR.empty(), IR.empty(), moduleIdentifier.cloneNode());
        importNode.srcref(export);
        importNode.insertBefore(export);
        visit(t, importNode, parent);

        String moduleName = getVarNameOfImport(moduleIdentifier.getString());
        export.replaceWith(
                IR.exprResult(
                                IR.call(IR.getprop(IR.name(REQUIRE), "exportCopy"), IR.name(MODULE), IR.name(moduleName)))
                        .srcrefTree(export));

        t.reportCodeChange();
    }

    private void visitExport(NodeTraversal t, Node export, Node parent) {
        if (export.getBooleanProp(Node.EXPORT_DEFAULT)) {
            visitExportDefault(t, export);
        } else if (export.getBooleanProp(Node.EXPORT_ALL_FROM)) {
            visitExportStar(t, export, parent);
        } else if (export.hasTwoChildren()) {
            visitExportFrom(t, export, parent);
        } else {
            if (export.getFirstChild().isExportSpecs()) {
                visitExportSpecs(t, export);
            } else {
                visitExportDeclaration(t, export);
            }
        }
    }

    public static String rewrite(String source) {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setPrettyPrint(true);
        co.setEmitUseStrict(false);
        co.setLanguageIn(CompilerOptions.LanguageMode.UNSTABLE);
        co.setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE);

        cc.initOptions(co);
        SourceFile src = SourceFile.fromCode("convert.js", source);

        JsAst ast = new JsAst(src);
        Node node = ast.getAstRoot(cc);

        NodeTraversal.traverse(cc, node, new ShadowESModuleRewriter(cc, node));

        // FIXME: source maps? babel-worker never handled those either, so doesn't seem too needed
        // most npm code is minified or ugly in other ways anyways
        return cc.toSource(node);
    }

    public static void dumpCode(String code) {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setPrettyPrint(true);
        co.setLanguageIn(CompilerOptions.LanguageMode.UNSTABLE);
        co.setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE);

        cc.initOptions(co);

        SourceFile testFile = SourceFile.fromCode("test.js", code);

        JsAst ast = new JsAst(testFile);
        Node node = ast.getAstRoot(cc);

        System.out.println(node.toStringTree());
    }

    public static void main(String[] args) {

        RT.init();

        String code = "import x from \"@whatever/foo-bar.js\"; import * as y from \"./bar/baz.js\"; import { xyz as a, z } from \"whatever\";export let bar = 1; export * from \"whatever\"; export { foo } from \"whatever\";export default 2; use(x, y, z, a);";

        // dumpCode("import \"whatever\";");

        System.out.println(rewrite(code));
    }
}
