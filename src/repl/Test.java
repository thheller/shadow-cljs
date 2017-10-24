import java.io.FileOutputStream;
import java.io.IOException;

public class Test {

    public static void main(String[] args) throws IOException {
        for (;;) {
            int c = System.in.read();
            if (c == -1)
                break;
            System.out.print(c);
        }

        try (FileOutputStream fis = new FileOutputStream("test.end.txt")) {
            fis.write("exit".getBytes());
        }
    }
}
