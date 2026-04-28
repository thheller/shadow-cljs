package shadow.fswatch;

import clojure.lang.IPersistentMap;
import clojure.lang.ITransientMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import clojure.lang.RT;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class PollingFileWatcher implements AutoCloseable, IFileWatcher {

    private final Path root;
    private final Set<String> extensions;
    private final Map<Path, Long> lastModifiedMap = new HashMap<>();

    private int pollCount = 0;

    private final static Keyword KW_NEW = RT.keyword(null, "new");
    private final static Keyword KW_MOD = RT.keyword(null, "mod");
    private final static Keyword KW_DEL = RT.keyword(null, "del");

    public PollingFileWatcher(Path dir, Set<String> extensions) {
        this.root = dir.toAbsolutePath();
        this.extensions = extensions;
    }

    public void initialScan() throws IOException {
        Files.walkFileTree(root, new InitialScan());
        pollCount++;
    }

    public IPersistentMap quickScan() {
        ITransientMap changes = PersistentHashMap.EMPTY.asTransient();

        Iterator<Map.Entry<Path, Long>> it = lastModifiedMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Path, Long> entry = it.next();
            Path file = entry.getKey();
            Long lastModified = entry.getValue();

            try {
                long currentModified = Files.getLastModifiedTime(file).toMillis();
                if (currentModified > lastModified) {
                    entry.setValue(currentModified);
                    changes = addChange(changes, file, KW_MOD);
                }
            } catch (IOException e) {
                // assume file was deleted on any exception
                // FIXME: probably better way to detect that
                it.remove();
                changes = addChange(changes, file, KW_DEL);
            }
        }

        return changes.persistent();
    }

    public IPersistentMap fullScan() throws IOException {
        ITransientMap changes = PersistentHashMap.EMPTY.asTransient();
        Set<Path> foundFiles = new HashSet<>();

        boolean initial = pollCount == 0;

        FullScan visitor = new FullScan(foundFiles, initial, changes);

        Files.walkFileTree(root, visitor);

        changes = visitor.changes;

        // Check for deletions
        Iterator<Path> it = lastModifiedMap.keySet().iterator();
        while (it.hasNext()) {
            Path file = it.next();
            if (!foundFiles.contains(file)) {
                it.remove();
                if (!initial) {
                    changes = addChange(changes, file, KW_DEL);
                }
            }
        }

        return changes.persistent();
    }

    private ITransientMap addChangeToMap(ITransientMap map, String relativeName, Keyword kind) {
        return map.assoc(relativeName, kind);
    }

    private ITransientMap addChange(ITransientMap map, Path file, Keyword kind) {
        String relativeName = root.relativize(file).toString();
        return addChangeToMap(map, relativeName, kind);
    }

    private boolean matchesExtension(String name) {
        int idx = name.lastIndexOf(".");
        if (idx > 0) { // never interested in .foo files, i.e. nothing before dot
            String ext = name.substring(idx+1);
            return extensions.contains(ext);
        }
        return false;
    }

    public IPersistentMap pollForChanges() throws IOException {
        boolean doFullScan = (pollCount % 10 == 0);

        IPersistentMap result;
        if (doFullScan) {
            result = fullScan();
        } else {
            result = quickScan();
        }
        pollCount++;

        return result;
    }

    @Override
    public void close() throws Exception {
        lastModifiedMap.clear();
    }


    private class FullScan extends SimpleFileVisitor<Path> {
        final Set<Path> foundFiles;
        final boolean initial;
        ITransientMap changes;

        public FullScan(Set<Path> foundFiles, boolean initial, ITransientMap changes) {
            this.foundFiles = foundFiles;
            this.initial = initial;
            this.changes = changes;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (FileWatcher.shouldIgnoreDir(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (Files.isHidden(file) || file.getFileName().toString().startsWith(".#")) {
                return FileVisitResult.CONTINUE;
            }

            if (attrs.isRegularFile()) {
                String relativeName = root.relativize(file).toString();
                if (matchesExtension(relativeName)) {
                    foundFiles.add(file);
                    long currentModified = attrs.lastModifiedTime().toMillis();
                    Long lastModified = lastModifiedMap.get(file);

                    if (lastModified == null) {
                        lastModifiedMap.put(file, currentModified);
                        if (!initial) {
                            changes = addChangeToMap(changes, relativeName, KW_NEW);
                        }
                    } else if (currentModified > lastModified) {
                        lastModifiedMap.put(file, currentModified);
                        if (!initial) {
                            changes = addChangeToMap(changes, relativeName, KW_MOD);
                        }
                    }
                }
            }
            return FileVisitResult.CONTINUE;
        }
    }

    private class InitialScan extends SimpleFileVisitor<Path> {
        public InitialScan() {
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (FileWatcher.shouldIgnoreDir(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (Files.isHidden(file) || file.getFileName().toString().startsWith(".#")) {
                return FileVisitResult.CONTINUE;
            }

            if (attrs.isRegularFile()) {
                String relativeName = root.relativize(file).toString();
                if (matchesExtension(relativeName)) {
                    long currentModified = attrs.lastModifiedTime().toMillis();
                    lastModifiedMap.put(file, currentModified);
                }
            }
            return FileVisitResult.CONTINUE;
        }
    }

    public static void main(String[] args) throws IOException {
        PollingFileWatcher w = new PollingFileWatcher(Paths.get("node_modules"), Set.of("js"));

        long start = System.currentTimeMillis();

        w.initialScan();

        long now = System.currentTimeMillis();

        System.out.printf("initial = %d%n", now - start);
        System.out.println(w);

        start = System.currentTimeMillis();
        System.out.println(w.fullScan());
        now = System.currentTimeMillis();

        System.out.printf("full = %d%n", now - start);

        start = System.currentTimeMillis();
        System.out.println(w.quickScan());
        now = System.currentTimeMillis();

        System.out.printf("quick = %d%n", now - start);

    }
}
