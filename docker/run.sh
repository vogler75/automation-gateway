docker run --rm --name gateway -p 4000:4000 -p 1883:1883 -v $PWD/config.yaml:/app/config.yaml gateway
