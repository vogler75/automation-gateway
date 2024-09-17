FROM amazoncorretto:17-alpine
#RUN apk add libstdc++ gcompat # needed by QuestDB
#FROM eclipse-temurin:21
ARG APP_NAME=app
ADD app.tar /
RUN test "$APP_NAME" = "app" || mv /${APP_NAME} /app
RUN echo "/app/bin/${APP_NAME}" > /run.sh && chmod 755 /run.sh
WORKDIR /app
CMD exec /run.sh
