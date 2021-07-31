#!/bin/bash

build() {
  app="${1:-app}"
  sub=${2:-.}
  ver=${3:-latest}

  echo $app $sub $ver

  if [ $sub = "cluster" ]; then
    name="rocworks/automation-gateway:$ver-$app-cluster"
    app_with_path=../source/$sub/$app/build/distributions/$app.tar 
  else
    if [ $sub = "." ]; then
      name="rocworks/automation-gateway:$ver"
      app_with_path=../source/$app/build/distributions/$app.tar 
    else
      app=$app-$sub	    
      name="rocworks/automation-gateway:$ver-$sub"
      app_with_path=../source/$app/build/distributions/$app.tar 
    fi	   
  fi    

  if [ -f $app_with_path ]; then
    echo $app
    cp $app_with_path ./app.tar
    docker build --build-arg APP_NAME=$app -t $name .
    rm ./app.tar
  else
    echo "Please build the app ${app_with_path} with gradle first!" 
  fi
}

app="${1:-none}"
ver=${2:-latest}

if [ $app = "single" ]; then
  build app . $ver
  build app plc4x $ver
elif [ $app = "cluster" ]; then
  build gateway cluster $ver
  build opcua cluster $ver
  build plc4x cluster $ver
  build dds cluster $ver
  build cache cluster $ver
  build influxdb cluster $ver
  build iotdb cluster $ver
  build kafka cluster $ver
else
  echo "Usage $0 [single|cluster] [version]"
fi
