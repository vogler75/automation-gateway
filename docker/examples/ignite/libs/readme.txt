# Copy Vertx Libs to here.

version=4.0.3
cp `find ~/.gradle -name "vertx-core-${version}.jar"` .
cp `find ~/.gradle -name "vertx-ignite-${version}.jar"` .
