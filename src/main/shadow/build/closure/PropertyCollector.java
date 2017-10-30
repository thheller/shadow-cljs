package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.TokenUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PropertyCollector implements NodeTraversal.Callback, CompilerPass {
    private final AbstractCompiler compiler;
    public final Map<String, Set<String>> properties = new HashMap<>();

    public PropertyCollector(AbstractCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node node, Node parent) {
        return true;
    }

    // private in parser/Scanner.java
    public static boolean isIdentifierStart(char ch) {
        switch (ch) {
            case '$':
            case '_':
                return true;
            default:
                // Workaround b/36459436
                // When running under GWT, Character.isLetter only handles ASCII
                // Angular relies heavily on U+0275 (Latin Barred O)
                return ch == 0x0275
                        // TODO: UnicodeLetter also includes Letter Number (NI)
                        || Character.isLetter(ch);
        }
    }

    // TokenUtil.isJSIdentifier is package protected ...
    public static boolean isJSIdentifier(String s) {
        int length = s.length();
        if (length != 0 && isIdentifierStart(s.charAt(0))) {
            for (int i = 1; i < length; ++i) {
                if (Character.isIdentifierIgnorable(s.charAt(i)) || !Character.isJavaIdentifierPart(s.charAt(i))) {
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    public final static Set<String> ignoredProps = new HashSet<>();

    static {
        ignoredProps.add("exports");
        ignoredProps.add("module");
        ignoredProps.add("prototype");
    }

    private void addProperty(NodeTraversal t, String property) {
        if (!property.equals("exports") && !property.equals("module") && isJSIdentifier(property)) {
            String sourceName = t.getSourceName();
            Set<String> x = properties.get(sourceName);
            if (x == null) {
                x = new HashSet<>();
                properties.put(sourceName, x);
            }
            x.add(property);
        }
    }

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        // exports.foo = 1;
        // function Foo() {}
        // Foo.prototype.bar = 1;
        // exports.Foo = Foo;
        // find every property assign
        // extract property name
        // use as :externs in :advanced mode build using JS deps
        // profit?
        // FIXME: bad idea? probably way too many names we don't actually need to preserve
        if (node.isAssign()) {
            Node getProp = node.getFirstChild();
            // only collect assignments to props
            // thing.x = 1;
            // not
            // var x = 1;
            // var x; x = 1;
            if (getProp.isGetProp()) {
                Node name = getProp.getLastChild();
                if (!name.isString()) {
                    System.out.println("==== assign without string");
                    System.out.println(node.toStringTree());
                    System.out.println("====");
                } else {
                    String property = name.getString();
                    addProperty(t, property);
                }
            } else {
                // FIXME: other cases I'm missing?
                // System.out.println("===== assign without getprop");
                // System.out.println(node.toStringTree());
                // System.out.println("=====");
            }
        } else if (node.isObjectLit()) {
            for (int i = 0; i < node.getChildCount(); i++) {
                Node keyNode = node.getChildAtIndex(i);
                if (keyNode.isStringKey() || keyNode.isGetterDef() || keyNode.isSetterDef()) {
                    addProperty(t, keyNode.getString());
                } else {
                    System.out.println("==== object-lit without string key");
                    System.out.println(node.toStringTree());
                }
            }
        } else if (NodeUtil.isCallTo(node,"Object.defineProperty")) {
            Node property = node.getChildAtIndex(2);
            if (property.isString()) {
                addProperty(t, property.getString());
            }
        }
    }

    public void process(Node externs, Node root) {
        NodeTraversal.traverseEs6(compiler, root, this);
    }

    public static Node process(Compiler cc, SourceFile srcFile) {
        JsAst ast = new JsAst(srcFile);
        Node node = ast.getAstRoot(cc);

        JsAst.ParseResult result = (JsAst.ParseResult) node.getProp(Node.PARSE_RESULTS);

        PropertyCollector pass = new PropertyCollector(cc);
        NodeTraversal.traverseEs6(cc, node, pass);


        System.out.println(pass.properties);

        return node;
    }

    public static void main(String... args) {
        Compiler cc = new Compiler();

        CompilerOptions co = new CompilerOptions();
        co.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        co.setPrettyPrint(true);
        cc.initOptions(co);

        // SourceFile srcFile = SourceFile.fromFile("node_modules/@firebase/util/dist/cjs/src/crypt.js");
        SourceFile srcFile = SourceFile.fromFile("test/closure-inputs/assigns.js");
        cc.toSource(process(cc, srcFile));

    }
}
