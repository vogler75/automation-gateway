# Amazon Corretto
#FROM amazoncorretto:17-alpine

# libstdc++ is needed by QuestDB
#RUN apk add libstdc++ gcompat libc6-compat

# Eclipse-Temurin  also has the listdc++ which is needed by QuestDB
#FROM eclipse-temurin:21 

# Debian
FROM debian:bookworm-slim

# Set environment variables for non-interactive installations
ENV DEBIAN_FRONTEND=noninteractive

# Install OpenJDK and clean up
RUN apt-get update && \
    apt-get install -y --no-install-recommends openjdk-17-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Set JAVA_HOME environment variable
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="$JAVA_HOME/bin:${PATH}"

# Verify Java installation
RUN java -version

ARG APP_NAME=app
ADD app.tar /
RUN test "$APP_NAME" = "app" || mv /${APP_NAME} /app
RUN echo "/app/bin/${APP_NAME}" > /run.sh && chmod 755 /run.sh
WORKDIR /app
CMD exec /run.sh
