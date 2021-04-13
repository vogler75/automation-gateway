FROM openjdk:11-jre-slim
ARG APP_NAME=app
ENV GATEWAY_CLUSTER_TYPE=ignite
ENV GATEWAY_BUS_PORT=10000
ADD app.tar /
RUN if ["$APP_NAME" != "app"]; then mv /${APP_NAME} /app; fi
RUN echo "/app/bin/${APP_NAME}" > /run.sh && chmod 755 /run.sh
WORKDIR /app
CMD exec /run.sh
