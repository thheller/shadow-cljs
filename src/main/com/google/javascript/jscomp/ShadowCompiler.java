package com.google.javascript.jscomp;

import com.google.debugging.sourcemap.SourceMapConsumerV3;
import com.google.debugging.sourcemap.proto.Mapping;
import com.google.debugging.sourcemap.proto.Mapping.OriginalMapping;

import javax.annotation.Nullable;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ShadowCompiler extends Compiler {

    private final Map<String, SourceMapConsumerV3> consumerCache = new HashMap<>();

    public ShadowCompiler() {
        super();
    }

    public ShadowCompiler(PrintStream outStream) {
        super(outStream);
    }

    public ShadowCompiler(ErrorManager errorManager) {
        super(errorManager);
    }

    public void justSetOptions(CompilerOptions opts) {
        this.options = opts;
    }

    /**
     * fixing https://github.com/google/closure-compiler/issues/3825 by removing relative path logic
     * the inputs provided by shadow-cljs always use the full name and as such don't need that logic
     *
     * this makes source maps work again on windows
     */
    @Nullable
    @Override
    public OriginalMapping getSourceMapping(String sourceName, int lineNumber, int columnNumber) {
        try {
            if (sourceName == null) {
                return null;
            }

            SourceMapInput sourceMap = inputSourceMaps.get(sourceName);
            if (sourceMap == null) {
                return null;
            }

            SourceMapConsumerV3 consumer = consumerCache.get(sourceMap.getOriginalPath());

            if (consumer == null) {
                consumer = sourceMap.getSourceMap(this.getErrorManager());
                if (consumer == null) {
                    return null;
                }

                consumerCache.put(sourceMap.getOriginalPath(), consumer);
            }

            OriginalMapping result = consumer.getMappingForLine(lineNumber, columnNumber + 1);
            if (result == null) {
                return null;
            }

            return result.toBuilder()
                    .setOriginalFile(sourceName)
                    .setColumnPosition(result.getColumnPosition() - 1)
                    .build();
        } catch (Exception e) {
            // sometimes fails on windows trying to resolve [synthetic:1] sources
            return null;
        }
    }
}
