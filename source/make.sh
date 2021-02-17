base=$PWD

cd $base/core
mvn org.jetbrains.kotlin:kotlin-maven-plugin:1.4.30:compile
mvn install

cd $base/graphql
mvn org.jetbrains.kotlin:kotlin-maven-plugin:1.4.30:compile
mvn install

cd $base/logger-influx
mvn org.jetbrains.kotlin:kotlin-maven-plugin:1.4.30:compile
mvn install

cd $base/run-standalone
mvn org.jetbrains.kotlin:kotlin-maven-plugin:1.4.30:compile
mvn install

mvn org.apache.maven.plugins:maven-assembly-plugin:2.2-beta-5:single
