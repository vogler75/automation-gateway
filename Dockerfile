FROM openjdk:11.0.10-slim
WORKDIR /app
COPY target /app/target
COPY config-docker.yaml /app/config.yaml
COPY classpath.txt /app/classpath.txt
CMD java -cp `cat classpath.txt`:target/gateway-1.0-SNAPSHOT.jar at.rocworks.Main
