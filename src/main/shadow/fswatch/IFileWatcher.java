package shadow.fswatch;

import clojure.lang.IPersistentMap;

import java.io.IOException;

public interface IFileWatcher {
    IPersistentMap pollForChanges() throws IOException, InterruptedException;
}
