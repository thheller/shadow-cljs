package shadow.remote.runtime;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

public class LimitWriter extends Writer {
    private final int limit;
    private final StringWriter sw;

    public LimitWriter(int limit) {
        this.limit = limit;
        this.sw = new StringWriter(limit);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        sw.write(cbuf, off, len);

        if (sw.getBuffer().length() > this.limit) {
            throw new LimitReachedException();
        }
    }

    public String getString() {
        String res = this.sw.getBuffer().toString();

        if (res.length() > limit) {
            return res.substring(0, limit);
        } else {
            return res;
        }
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
    }

    public static class LimitReachedException extends IOException {
    }
}
