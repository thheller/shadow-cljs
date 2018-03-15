package com.google.javascript.jscomp;

import com.google.debugging.sourcemap.proto.Mapping;

import javax.annotation.Nullable;
import java.io.PrintStream;

public class ShadowCompiler extends Compiler {

    public ShadowCompiler() {
        super();
    }

    public ShadowCompiler(PrintStream outStream) {
        super(outStream);
    }

    public ShadowCompiler(ErrorManager errorManager) {
        super(errorManager);
    }

    @Nullable
    @Override
    public Mapping.OriginalMapping getSourceMapping(String sourceName, int lineNumber, int columnNumber) {
        try {
            return super.getSourceMapping(sourceName, lineNumber, columnNumber);
        } catch (Exception e) {
            // sometimes fails on windows trying to resolve [synthetic:1] sources
            return null;
        }
    }
}
