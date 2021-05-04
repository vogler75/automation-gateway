docker run --rm --name frankenstein-app -p 4000:4000 -p 1883:1883 -p 10800:10800 -v $PWD/../examples/configs/app.yaml:/app/config.yaml $app
