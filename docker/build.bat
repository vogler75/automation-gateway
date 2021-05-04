copy ..\source\app\build\distributions\app.tar app.tar
docker build --build-arg APP_NAME=app -t frankenstein-app .
del app.tar

copy ..\source\cluster\gateway\build\distributions\gateway.tar app.tar
docker build --build-arg APP_NAME=gateway -t frankenstein-gateway .
del app.tar

copy ..\source\cluster\opcua\build\distributions\opcua.tar app.tar
docker build --build-arg APP_NAME=opcua -t frankenstein-opcua .
del app.tar

copy ..\source\cluster\cache\build\distributions\cache.tar app.tar
docker build --build-arg APP_NAME=cache -t frankenstein-cache .
del app.tar

copy ..\source\cluster\influxdb\build\distributions\influxdb.tar app.tar
docker build --build-arg APP_NAME=influxdb -t frankenstein-influxdb .
del app.tar

copy ..\source\cluster\plc4x\build\distributions\plc4x.tar app.tar
docker build --build-arg APP_NAME=plc4x -t frankenstein-plc4x .
del app.tar
