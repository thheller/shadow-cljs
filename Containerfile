FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/opt/java
ENV NODE_HOME=/opt/node
ENV PATH=$JAVA_HOME/bin:$NODE_HOME/bin:$PATH

# Install base dependencies
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    gnupg \
    tar \
    git \
    make \
    build-essential \
    rlwrap \
    && rm -rf /var/lib/apt/lists/*

# Install Eclipse Temurin Java 25
RUN mkdir -p /opt/java && \
    curl -L "https://api.adoptium.net/v3/binary/latest/25/ga/linux/aarch64/jdk/hotspot/normal/eclipse" | tar -xz -C /opt/java --strip-components=1

# Install Node.js 25.8.2
RUN mkdir -p /opt/node && \
    curl -L "https://nodejs.org/dist/v25.8.2/node-v25.8.2-linux-arm64.tar.xz" | tar -xJ -C /opt/node --strip-components=1

# Install Clojure CLI
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh && \
    rm linux-install.sh

# Install Leiningen
RUN curl -L -o /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    chmod a+x /usr/local/bin/lein && \
    lein help

USER root

WORKDIR /code/shadow-cljs

CMD ["./container-start-dev.sh"]