package shadow.util;

import clojure.lang.*;
import com.sun.nio.file.SensitivityWatchEventModifier;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watchservice.MacOSXListeningWatchService;
import io.methvin.watchservice.WatchablePath;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.*;

public class FileWatcher implements AutoCloseable {

    private final Path root;
    private final WatchService ws;
    private final Map<WatchKey, Path> keys;
    private final boolean isMac;
    private final List<String> extensions;

    FileWatcher(Path dir, List<String> extensions) throws IOException {
        this.root = dir.toAbsolutePath();

        this.isMac = System.getProperty("os.name").toLowerCase().contains("mac");

        if (this.isMac) {
            // don't know exactly what this fileHasher is about but the default directory-watcher does this
            // https://github.com/gmethvin/directory-watcher/blob/1d705974a37f34945c3cb90c0ecdeb900a2da62c/core/src/main/java/io/methvin/watcher/DirectoryWatcher.java#L129-L142
            this.ws = new MacOSXListeningWatchService(
                    new MacOSXListeningWatchService.Config() {
                        @Override
                        public FileHasher fileHasher() {
                            return null;
                        }
                    });
        } else {
            this.ws = this.root.getFileSystem().newWatchService();
        }

        this.keys = new HashMap<>();
        this.extensions = extensions;
        registerAll(this.root);
    }


    @Override
    public void close() throws Exception {
        this.keys.clear();
        this.ws.close();
    }

    private Watchable asWatchable(Path dir) {
        if (this.isMac) {
            return new WatchablePath(dir);
        } else {
            return dir;
        }
    }

    @SuppressWarnings("unchecked")
    private Path eventPath(WatchEvent event) {
        WatchEvent<Path> ev = (WatchEvent<Path>) event;
        return ev.context();
    }

    private void registerAll(final Path start) throws IOException {
        Files.walkFileTree(
                start,
                EnumSet.allOf(FileVisitOption.class),
                Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        WatchKey key = asWatchable(dir).register(ws,
                                new WatchEvent.Kind[]{
                                        ENTRY_CREATE,
                                        ENTRY_DELETE,
                                        ENTRY_MODIFY
                                }, SensitivityWatchEventModifier.HIGH); // OSX is way too slow without this
                        keys.put(key, dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private boolean matchesExtension(String name) {
        for (String ext: extensions) {
           if (name.endsWith("." + ext)) {
               return true;
           }
        }

        return false;
    }


    private final static Keyword KW_NEW = RT.keyword(null, "new");
    private final static Keyword KW_MOD = RT.keyword(null, "mod");
    private final static Keyword KW_DEL = RT.keyword(null, "del");

    /**
     * blocking operation to gather all changes, blocks until at least one change happened
     *
     * @return {"path-to-file" :new|:mod|:del}
     * @throws IOException
     * @throws InterruptedException
     */
    public IPersistentMap waitForChanges() throws IOException, InterruptedException {
        return pollForChanges(true);
    }

    public IPersistentMap pollForChanges() throws IOException, InterruptedException {
        return pollForChanges(false);
    }

    @SuppressWarnings("unchecked")
    IPersistentMap pollForChanges(boolean block) throws IOException, InterruptedException {
        ITransientMap changes = PersistentHashMap.EMPTY.asTransient();

        WatchKey key;
        if (block) {
            key = ws.take();
        } else {
            key = ws.poll();
        }

        while (key != null) {
            Path dir = keys.get(key);
            if (dir == null) {
                throw new IllegalStateException("got a key for a path we don't know: " + key);
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind kind = event.kind();

                if (kind == OVERFLOW) {
                    continue;
                }

                Path name = eventPath(event);
                Path resolvedName = dir.resolve(name);
                Path child = root.relativize(resolvedName);
                String childName = child.toString();

                if (Files.isDirectory(resolvedName)) {
                    // monitor new directories
                    // deleted directories will cause the key to become invalid and removed later
                    // not interested in modify
                    if (kind == ENTRY_CREATE) {
                        registerAll(resolvedName);
                    }
                } else if (matchesExtension(childName)) {
                    if (kind == ENTRY_DELETE) {
                        changes = changes.assoc(childName, KW_DEL);
                    } else {
                        // windows is really picky here, fails with exception when asking if a
                        // deleted file is hidden
                        // intellij on windows seems to
                        // create a temp file
                        // modify the temp file
                        // swap temp file -> real file
                        // delete temp file
                        // for every file save, this really confuses the watcher
                        if (Files.exists(resolvedName) && !Files.isHidden(resolvedName)) {
                            if (kind == ENTRY_CREATE) {
                                changes = changes.assoc(childName, KW_NEW);
                            } else if (kind == ENTRY_MODIFY) {
                                changes = changes.assoc(childName, KW_MOD);
                            }
                        }
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) { // deleted dirs are no longer valid
                keys.remove(key);
            }

            if (block && changes.count() == 0) {
                // if no interesting changes happened, continue to block
                // eg. empty directory created, "unwanted" file created/deleted
                key = ws.take();
            } else {
                // peek at potential other changes, will terminate loop if nothing is waiting
                key = ws.poll();
            }
        }

        return changes.persistent();
    }

    public static FileWatcher create(Path dir, List<String> extensions) throws IOException {
        return new FileWatcher(dir, extensions);
    }

    public static FileWatcher create(File dir, List<String> extensions) throws IOException {
        return create(dir.toPath(), extensions);
    }
}
