package shadow.util;

import clojure.lang.*;
import java.io.*;
import java.nio.CharBuffer;

/**
 * reads clojure but discards the form that was read and instead returns the string of the form
 * don't feel like writing an actual lisp parser that does this, so just using the clojure parser
 *
 */
public class FakeLispReader {
    private final Object EOF = new Object();

    private StringBuilder sb;
    private final PushbackReader in;

    public FakeLispReader() {
        this(System.in);
    }

    public FakeLispReader(InputStream in) {
        this.sb = new StringBuilder();
        this.in = new PushbackReader(new InputStreamReader(in)) {
            @Override
            public int read() throws IOException {
                int c = super.read();
                if (c != -1) {
                    sb.append((char)c);
                }
                return c;
            }

            @Override
            public void unread(int c) throws IOException {
                sb.deleteCharAt(sb.length() - 1);
                super.unread(c);
            }

            @Override
            public int read(char[] cbuf, int off, int len) throws IOException {
                throw new IllegalStateException("clojure doesn't use this?");
            }

            @Override
            public void unread(char[] cbuf, int off, int len) throws IOException {
                throw new IllegalStateException("clojure doesn't use this?");
            }

            @Override
            public void unread(char[] cbuf) throws IOException {
                throw new IllegalStateException("clojure doesn't use this?");
            }

            @Override
            public long skip(long n) throws IOException {
                throw new IllegalStateException("clojure doesn't use this?");
            }

            @Override
            public int read(CharBuffer target) throws IOException {
                throw new IllegalStateException("clojure doesn't use this?");
            }

            @Override
            public int read(char[] cbuf) throws IOException {
                throw new IllegalStateException("clojure doesn't use this?");
            }
        };
    }

    private static final Keyword READ_COND = RT.keyword(null, "read-cond");
    private static final Keyword PRESERVE = RT.keyword(null, "preserve");
    private static final IFn DONT_CARE_ABOUT_TAGGED_VALUES = new AFn() {
        @Override
        public Object invoke(Object arg1, Object arg2) {
            return arg2;
        }
    };

    public String next() throws Exception {
        final Object readerFn = RT.DEFAULT_DATA_READER_FN.deref();

        Object form;

        try {
            RT.DEFAULT_DATA_READER_FN.doReset(DONT_CARE_ABOUT_TAGGED_VALUES);
            form = LispReader.read(in, false, EOF, false, RT.map(READ_COND, PRESERVE));
        } catch (Exception e) {
            sb = new StringBuilder();
            throw e;
        } finally {
            RT.DEFAULT_DATA_READER_FN.doReset(readerFn);
        }

        if (EOF == form) {
            return null;
        } else {
            String result = sb.toString().trim();

            sb = new StringBuilder();

            return result;
        }
    }


    public static void main(String[] args) throws IOException {
        FakeLispReader in = new FakeLispReader(System.in);

        while (true) {
            try {
                String token = in.next();
                if (token == null) {
                    break;
                }

                System.out.printf("completed form:%n%s%n", token);
            } catch (Exception e) {
                System.out.println("error: " + e.getMessage());
            }

        }
    }
}
