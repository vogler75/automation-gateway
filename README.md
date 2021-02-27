# OPC UA Gateway for GraphQL and MQTT

Connect one or more OPC UA servers to the gateway and access the data from the OPC UA servers with a GraphQL or a MQTT client.

![Gateway](doc/Gateway.png)

# Build and Run

It needs [JDK 11](https://openjdk.java.net/projects/jdk/11/).

You can open the project in IntelliJ IDEA IDE and build it there or use grade to build it from command line.

```
> cd app
> gradle run
```

App is a single program with GraphQL, MQTT and the OPC UA connections in one single program. There is also an option to let it run in clustered mode. Then the modules (app-gateway, app-graphql, ...) run in separate processes and form a cluster with a distributed communication bus based on [Vert.x](https://vertx.io). With that option the modules can run on different nodes or they can be deployed in a Kubernetes Cluster.

## Configuration

See config-example.yaml for an example how to configure the Gateway. You can pass a configuration file name to the program as the first argument. If no argument is given then config.yaml will be used.


## Build Docker Image

You have to build the program before with gradle. Then you can use the shell script `docker/build.sh` to build a docker image.  
`docker run --rm --name gateway -p 4000:4000 -p 1883:1883 -v $PWD/config.yaml:/app/config.yaml gateway`
