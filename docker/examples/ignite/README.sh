# Copy Vertx Libs to the lib directory.

version=4.0.3
mkdir -p libs
cp `find ~/.gradle -name "vertx-core-${version}.jar"` libs
cp `find ~/.gradle -name "vertx-ignite-${version}.jar"` libs
