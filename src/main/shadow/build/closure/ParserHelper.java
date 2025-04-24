package shadow.build.closure;

import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.RT;
import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.ShadowAccess;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.parsing.ParserRunner;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.rhino.ErrorReporter;
import com.google.javascript.rhino.Node;

import java.io.IOException;

public class ParserHelper implements ErrorReporter {
    public static final Keyword KW_LINE = RT.keyword(null, "line");
    public static final Keyword KW_COLUMN = RT.keyword(null, "column");
    public static final Keyword KW_MESSAGE = RT.keyword(null, "message");

    public IPersistentVector warnings = RT.vector();
    public IPersistentVector errors = RT.vector();
    public Node ast;
    public FeatureSet features;

    ParserHelper() {
    }

    @Override
    public void warning(String message, String sourceName, int line, int lineOffset) {
        warnings = (IPersistentVector) warnings.assoc(warnings.length(), RT.map(
                KW_MESSAGE, message,
                KW_LINE, line,
                KW_COLUMN, lineOffset));
    }

    @Override
    public void error(String message, String sourceName, int line, int lineOffset) {
        errors = (IPersistentVector) errors.assoc(errors.length(), RT.map(
                KW_MESSAGE, message,
                KW_LINE, line,
                KW_COLUMN, lineOffset));
    }

    public static ParserHelper parse(AbstractCompiler cc, SourceFile srcFile) throws IOException {
        ParserHelper helper = new ParserHelper();

        ParserRunner.ParseResult result =
                ParserRunner.parse(
                        srcFile,
                        srcFile.getCode(),
                        ShadowAccess.getParserConfig(cc),
                        helper);

        helper.ast = result.ast;
        helper.features = result.features;

        // FIXME: result.comments, result.sourceMapURL?

        return helper;
    }
}
