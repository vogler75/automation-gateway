FROM openjdk:11-jre-slim
ARG APP_NAME=app
ENV GATEWAY_CLUSTER_TYPE=ignite
ADD app.tar /
RUN test "$APP_NAME" = "app" || mv /${APP_NAME} /app
RUN echo "/app/bin/${APP_NAME}" > /run.sh && chmod 755 /run.sh
WORKDIR /app
CMD exec /run.sh
