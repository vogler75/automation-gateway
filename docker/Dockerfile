#FROM amazoncorretto:17-alpine
# libstdc++ is needed by QuestDB
#RUN apk add libstdc++ gcompat 

# Eclipse-Temurin  also has the listdc++ which is needed by QuestDB
FROM eclipse-temurin:21 

ARG APP_NAME=app
ADD app.tar /
RUN test "$APP_NAME" = "app" || mv /${APP_NAME} /app
RUN echo "/app/bin/${APP_NAME}" > /run.sh && chmod 755 /run.sh
WORKDIR /app
CMD exec /run.sh
