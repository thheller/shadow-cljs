package shadow.util;

import clojure.lang.IPersistentCollection;
import clojure.lang.ITransientCollection;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentVector;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Created by zilence on 05.06.15.
 */
public class FS {

    public static abstract class PersistentFileVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private ITransientCollection result;

        public PersistentFileVisitor(Path root) {
            this.root = root;
            this.result = PersistentVector.EMPTY.asTransient();
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (dir.toFile().isHidden()) {
                return FileVisitResult.SKIP_SUBTREE;
            } else {
                return super.preVisitDirectory(dir, attrs);
            }
        }

        public abstract boolean keep(Path path);

        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
            // need to relativize path to root, otherwise glob is too picky
            if (!path.toFile().isHidden() && keep(root.relativize(path))) {
                result = result.conj(path.toFile());
            }
            return FileVisitResult.CONTINUE;
        }

        public IPersistentCollection getResult() {
            return result.persistent();
        }
    }

    /**
     * if I walk with a glob of "*.scss" it will not match .scss files recursivly
     * if I walk with a glob of "**\/*.scss" it will not match .scss file in the root
     *
     * how do get all files via glob?
     */

    /**
     * finds all files matching given extension recursively
     *
     * @param root
     * @param ext
     * @return
     * @throws IOException
     */
    public static IPersistentCollection findFilesByExt(Path root, String ext) throws IOException {
        final PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:*." + ext);
        PersistentFileVisitor visitor = new PersistentFileVisitor(root) {
            @Override
            public boolean keep(Path path) {
                return matcher.matches(path.getFileName()); // only matches the filename, path is ignored
            }
        };
        Files.walkFileTree(root, new HashSet<>(), Integer.MAX_VALUE, visitor);
        return visitor.getResult();
    }

    public static IPersistentCollection glob(Path root, String pattern) throws IOException {
        final PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + pattern);
        PersistentFileVisitor visitor = new PersistentFileVisitor(root) {
            @Override
            public boolean keep(Path path) {
                return matcher.matches(path);
            }
        };
        Files.walkFileTree(root, new HashSet<>(), Integer.MAX_VALUE, visitor);
        return visitor.getResult();
    }

    public static IPersistentCollection glob(File root, String pattern) throws IOException {
        return glob(root.toPath(), pattern);
    }

    // making vargs more convenient for Clojure
    public static Path path(String p1) {
        return Paths.get(p1);
    }

    public static Path path(String p1, String p2) {
        return Paths.get(p1, p2);
    }

    public static Path path(String p1, String p2, String p3) {
        return Paths.get(p1, p2, p3);
    }

    public static Path path(String p1, String p2, String p3, String p4) {
        return Paths.get(p1, p2, p3, p4);
    }

    public static Path path(String p1, String p2, String p3, String p4, String p5) {
        return Paths.get(p1, p2, p3, p4, p5);
    }
}

