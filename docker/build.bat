copy ..\source\app\build\distributions\app.tar app.tar
docker build --build-arg APP_NAME=app -t gateway .
del app.tar

copy ..\source\cluster-gateway\build\distributions\cluster-gateway.tar app.tar
docker build --build-arg APP_NAME=cluster-gateway -t cluster-gateway .
del app.tar

copy ..\source\cluster-opcua\build\distributions\cluster-opcua.tar app.tar
docker build --build-arg APP_NAME=cluster-opcua -t cluster-opcua .
del app.tar

copy ..\source\cluster-cache\build\distributions\cluster-cache.tar app.tar
docker build --build-arg APP_NAME=cluster-cache -t cluster-cache .
del app.tar

copy ..\source\cluster-influxdb\build\distributions\cluster-influxdb.tar app.tar
docker build --build-arg APP_NAME=cluster-influxdb -t cluster-influxdb .
del app.tar

copy ..\source\cluster-plc4x\build\distributions\cluster-plc4x.tar app.tar
docker build --build-arg APP_NAME=cluster-plc4x -t cluster-plc4x .
del app.tar
