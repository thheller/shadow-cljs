package shadow.build.closure;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentHashMap;
import com.google.debugging.sourcemap.FilePosition;
import com.google.debugging.sourcemap.SourceMapConsumerV3;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SourceMapReport {

    public static class Visitor implements SourceMapConsumerV3.EntryVisitor {
        public final Map<String, Long> bytes = new HashMap<>();
        public final List<String> lines;

        public Visitor(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public void visit(String sourceName, String symbolName, FilePosition sourceStartPosition, FilePosition startPos, FilePosition endPos) {
            long diff = 0;

            if (startPos.getLine() == endPos.getLine()) {
                diff = endPos.getColumn() - startPos.getColumn();
            } else {
                int line = startPos.getLine();
                int lineDiff = endPos.getLine() - line;
                if (lineDiff > 1) {
                    throw new IllegalStateException("TBD, more than one line between mappings");
                }
                diff = (lines.get(line).length() - startPos.getColumn());
                diff += endPos.getColumn();
            }

            Long prev = bytes.get(sourceName);
            if (prev == null) {
                bytes.put(sourceName, diff);
            } else {
                bytes.put(sourceName, prev + diff);
            }
        }
    }

    public static IPersistentMap getByteMap(File sourceFile, File sourceMapFile) throws Exception {
        List<String> lines = Files.readAllLines(sourceFile.toPath());
        String content = new String(Files.readAllBytes(sourceMapFile.toPath()));

        SourceMapConsumerV3 sm = new SourceMapConsumerV3();
        sm.parse(content);

        Visitor visitor = new Visitor(lines);
        sm.visitMappings(visitor);

        return PersistentHashMap.create(visitor.bytes);
    }

    public static void main(String[] args) throws Exception {
        File sourceFile = new File(".shadow-cljs/release-info/demo.js");
        File sourceMapFile = new File(".shadow-cljs/release-info/demo.js.map");

        System.out.println(getByteMap(sourceFile, sourceMapFile));
    }
}
