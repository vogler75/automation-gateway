export JAVA_OPTS=-agentlib:native-image-agent=config-merge-dir=config$1
./source/app$1/bin/app$1
