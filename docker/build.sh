#!/bin/bash

build() {
  app="${1:-app}"
  dir=${2:-.}
  app_with_path=../source/$dir/$app/build/distributions/$app.tar 
  if [ -f $app_with_path ]; then
    echo $app
    cp $app_with_path ./app.tar
    docker build --build-arg APP_NAME=$app -t frankenstein-$app .
    rm ./app.tar
  else
    echo "Please build the app with gradle first!" 
  fi
}

app="${1:-none}"

if [ $app = "app" ]; then
  build app
elif [ $app = "cluster" ]; then
  build gateway cluster
  build opcua cluster
  build plc4x cluster
  build dds cluster
  build cache cluster
  build influxdb cluster
  build iotdb cluster
  build kafka cluster
else
  echo "Usage $0 [app|cluster]"
fi
