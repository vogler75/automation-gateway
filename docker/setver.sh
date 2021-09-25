#!/bin/bash
v=`cat version.txt`
version="${1:-$v}"
if [ $version = "none" ]; then
  echo "usage $0 <version>"
else  
  echo "tag version $version"	
  docker tag rocworks/automation-gateway:latest rocworks/automation-gateway:$version
  docker tag rocworks/automation-gateway:latest-plc4x rocworks/automation-gateway:${version}-plc4x
fi  
