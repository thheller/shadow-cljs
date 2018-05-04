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

            // we only want to count the = chars
            if (startPos.getLine() == endPos.getLine()) {
                // -----+===============================+-----------------
                diff = endPos.getColumn() - startPos.getColumn();
            } else {
                // -------------------------+=============================
                // =======================================================
                // =======================================================
                // ================+--------------------------------------
                int line = startPos.getLine();
                int lineDiff = endPos.getLine() - line;
                diff = (lines.get(line).length() - startPos.getColumn());
                for (int i = 1; i < lineDiff; i++) {
                    diff += lines.get(line + i).length();
                }
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

}
