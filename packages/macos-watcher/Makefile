CC = clang
CFLAGS = -Wall -Wextra -O2 -mmacosx-version-min=13.0
LDFLAGS = -framework CoreServices
TARGET = macos-fswatch

.PHONY: all clean

all: $(TARGET)

$(TARGET): main.c
	$(CC) $(CFLAGS) -arch arm64 -arch x86_64 $(LDFLAGS) -o $@ $<
	cp $(TARGET) ../../src/main/shadow/fswatch/

clean:
	rm -f $(TARGET)
