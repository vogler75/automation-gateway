echo "Build..."
cd ../source
gradle build

echo "Unpack..."
cd ../native
rm -r source/app
tar xf ../source/app$1/build/distributions/app$1.tar -C source
echo `cat source/app$1/bin/app | grep "^CLASSPATH" | sed "s/^CLASSPATH=//"` > classpath.txt

echo "Ready."
