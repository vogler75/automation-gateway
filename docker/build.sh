#!/bin/bash

build() {
  app="${1:-app}"
  app_with_path=../source/$app/build/distributions/$app.tar 
  if [ -f $app_with_path ]; then
    echo $app
    cp $app_with_path ./app.tar
    docker build --build-arg APP_NAME=$app -t $app .
    rm ./app.tar
  else
    echo "Please build the app with gradle first!" 
  fi
}

build app
build cluster-gateway
build cluster-opcua
build cluster-cache
build cluster-influxdb
build cluster-plc4x
build cluster-dds