app="${1:-app}"
app_with_path=../source/$app/build/distributions/$app.tar 
if [ -f $app_with_path ]; then
  cp $app_with_path ./app.tar
  docker build --build-arg APP_NAME=$app -t $app .
  rm ./app.tar
else
  echo "Please build the app with gradle first!" 
fi
