version=`cat version.txt`

docker push rocworks/automation-gateway:${version}
#docker push rocworks/automation-gateway:latest 

docker push rocworks/automation-gateway:${version}-plc4x
#docker push rocworks/automation-gateway:latest-plc4x 
