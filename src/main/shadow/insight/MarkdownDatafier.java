package shadow.insight;

import clojure.lang.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

public class MarkdownDatafier implements Visitor {

    public static final Keyword KW_CHILDREN = RT.keyword(null, "children");

    private Associative current = startNew();

    @Override
    public void visit(BlockQuote node) {
        assocKeyword("type", "block-quote");
        visitChildren(node);
    }

    @Override
    public void visit(BulletList node) {
        assocKeyword("type", "bullet-list");
        assocKeyword("marker", node.getBulletMarker());
        visitChildren(node);
    }

    @Override
    public void visit(Code node) {
        assocKeyword("type", "code");
        visitChildren(node);
    }

    @Override
    public void visit(Document node) {
        assocKeyword("type", "document");
        visitChildren(node);
    }

    @Override
    public void visit(Emphasis node) {
        assocKeyword("type", "emphasis");
        assocKeyword("open", node.getOpeningDelimiter());
        assocKeyword("close", node.getClosingDelimiter());
        visitChildren(node);
    }

    @Override
    public void visit(FencedCodeBlock node) {
        assocKeyword("type", "fenced-code-block");
        assocKeyword("info", node.getInfo());
        assocKeyword("literal", node.getLiteral());
        assocKeyword("fence-char", node.getFenceChar());
        assocKeyword("fence-indent", node.getFenceIndent());
        assocKeyword("fence-length", node.getFenceLength());
        visitChildren(node);
    }

    @Override
    public void visit(HardLineBreak node) {
        assocKeyword("type", "hard-line-break");
        visitChildren(node);
    }

    @Override
    public void visit(Heading node) {
        assocKeyword("type", "heading");
        assocKeyword("level", node.getLevel());
        visitChildren(node);
    }

    @Override
    public void visit(ThematicBreak node) {
        assocKeyword("type", "thematic-break");
        visitChildren(node);
    }

    @Override
    public void visit(HtmlInline node) {
        assocKeyword("type", "html-inline");
        assocKeyword("literal", node.getLiteral());
        visitChildren(node);
    }

    @Override
    public void visit(HtmlBlock node) {
        assocKeyword("type", "html-block");
        assocKeyword("literal", node.getLiteral());
        visitChildren(node);
    }

    @Override
    public void visit(Image node) {
        assocKeyword("type", "image");
        assocKeyword("destination", node.getDestination());
        assocKeyword("title", node.getTitle());
        visitChildren(node);
    }

    @Override
    public void visit(IndentedCodeBlock node) {
        assocKeyword("type", "indented-code-block");
        assocKeyword("literal", node.getLiteral());
        visitChildren(node);
    }

    @Override
    public void visit(Link node) {
        assocKeyword("type", "link");
        assocKeyword("destination", node.getDestination());
        assocKeyword("title", node.getTitle());
        visitChildren(node);
    }

    @Override
    public void visit(ListItem node) {
        assocKeyword("type", "list-item");
        visitChildren(node);
    }

    @Override
    public void visit(OrderedList node) {
        assocKeyword("type", "ordered-list");
        assocKeyword("start-number", node.getStartNumber());
        assocKeyword("delimiter", node.getDelimiter());
        visitChildren(node);
    }

    @Override
    public void visit(Paragraph node) {
        assocKeyword("type", "paragraph");
        visitChildren(node);
    }

    @Override
    public void visit(SoftLineBreak node) {
        assocKeyword("type", "soft-line-break");
        visitChildren(node);
    }

    @Override
    public void visit(StrongEmphasis node) {
        assocKeyword("type", "strong-emphasis");
        assocKeyword("open", node.getOpeningDelimiter());
        assocKeyword("close", node.getClosingDelimiter());
        visitChildren(node);
    }

    @Override
    public void visit(Text node) {
        assocKeyword("type", "text");
        assocKeyword("literal", node.getLiteral());
        visitChildren(node);
    }

    @Override
    public void visit(LinkReferenceDefinition node) {
        assocKeyword("type", "link-reference-definition");
        assocKeyword("destination", node.getDestination());
        assocKeyword("title", node.getTitle());
        assocKeyword("label", node.getLabel());
        visitChildren(node);
    }

    @Override
    public void visit(CustomBlock node) {
        assocKeyword("type", "custom-block");
        visitChildren(node);
    }

    @Override
    public void visit(CustomNode node) {
        assocKeyword("type", "custom-node");
        visitChildren(node);
    }

    private void assocKeyword(String kw, Object val) {
        current = RT.assoc(current, RT.keyword(null, kw), val);
    }

    public static IPersistentMap startNew() {
        return RT.map();
    }


    protected void visitChildren(Node parent) {
        Associative self = current;
        IPersistentCollection children = RT.vector();

        Node node = parent.getFirstChild();
        while (node != null) {
            Node next = node.getNext();

            current = startNew();

            node.accept(this);
            children = RT.conj(children, current);

            node = next;
        }

        if (children.count() > 0) {
            current = RT.assoc(self, KW_CHILDREN, children);
        } else {
            current = self;
        }
    }

    public static Associative convert(Node node) {
        MarkdownDatafier v = new MarkdownDatafier();
        node.accept(v);
        return v.current;
    }

    public static void main(String[] node) {
        Parser parser = Parser.builder().build();
        Node document = parser.parse("# yo\n## foo\nhello world\n```clojure\nfoo\n```\nThis `is` *Sparta*");
        System.out.println(convert(document));
    }
}
