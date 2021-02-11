mvn kotlin:compile
mvn package dependency:copy-dependencies dependency:build-classpath -Dmdep.outputFile=classpath.txt -Dmdep.prefix="target/dependency"
