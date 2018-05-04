package shadow.build.closure;

import java.io.File;

public class SourceMapReportTest {

    public static void main(String[] args) throws Exception {
        File releaseDir = new File(".shadow-cljs/release-snapshots/browser/latest");
        File sourceFile = new File(releaseDir, "demo.js");
        File sourceMapFile = new File(releaseDir, "demo.js.map");

        System.out.println(SourceMapReport.getByteMap(sourceFile, sourceMapFile));
    }
}
