package shadow.build.closure;

import com.google.javascript.jscomp.*;
import com.google.javascript.rhino.Node;

import java.util.HashSet;
import java.util.Set;

public class FindSurvivingRequireCalls extends NodeTraversal.AbstractPostOrderCallback {

    final Set<Object> survivors = new HashSet<>();

    @Override
    public void visit(NodeTraversal t, Node node, Node parent) {
        if (node.isCall() && node.getChildCount() == 2) {
            Node name = node.getFirstChild();
            Node arg = node.getSecondChild();

            Node refName;

            if (name.isName()) {
                refName = name;
            } else if (name.isGetProp() || name.getFirstChild().isName()) {
                refName = name.getFirstChild();
            } else {
                return;
            }

            String originalName = refName.getOriginalName();

            if (originalName != null && "require".equals(originalName)) {
                if (arg.isString()) {
                    survivors.add(arg.getString());
                } else if (arg.isNumber()) {
                    survivors.add(((Double)arg.getDouble()).longValue());
                }
            }
        }
    }

    public static Set<Object> find(AbstractCompiler compiler, Node node) {
        FindSurvivingRequireCalls visitor = new FindSurvivingRequireCalls();
        NodeTraversal.traverse(compiler, node, visitor);
        return visitor.survivors;
    }
}
