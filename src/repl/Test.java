import java.io.FileOutputStream;

public class Test implements Runnable {

    public void run() {
        try {
            for (; ; ) {
                int c = System.in.read();
                if (c == -1)
                    break;
                System.out.print(c);
            }

            try (FileOutputStream fis = new FileOutputStream("test.end.txt")) {
                fis.write("exit".getBytes());
            }
        } catch (Exception e) {
            try (FileOutputStream fis = new FileOutputStream("test.ex.txt")) {
                fis.write(e.getMessage().getBytes());
            } catch (Exception e2) {
            }
        }
    }

    public static void main(String[] args) {
        new Thread(new Test()).start();
    }
}
