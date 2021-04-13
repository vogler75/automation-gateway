app="${1:-app}"

docker run --rm --name $app -p 4000:4000 -p 1883:1883 -p 10800:10800 -v $PWD/configs/$app.yaml:/app/config.yaml $app
