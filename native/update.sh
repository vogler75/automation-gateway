echo "Build..."
cd ../source
gradle build

echo "Unpack..."
cd ../native
rm -r source/app
tar xf ../source/app/build/distributions/app.tar -C source
echo `cat source/app/bin/app | grep "^CLASSPATH" | sed "s/^CLASSPATH=//"` > classpath.txt

echo "Ready."
