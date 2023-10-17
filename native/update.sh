echo "Build..."
cd ../source
./gradlew build

echo "Unpack..."
cd ../native
rm -rf source/app$1
tar xf ../source/app$1/build/distributions/app$1.tar -C source
echo `cat source/app$1/bin/app$1 | grep "^CLASSPATH" | sed "s/^CLASSPATH=//"` > classpath.txt

echo "Ready."
