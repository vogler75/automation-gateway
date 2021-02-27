APP=../source/app/build/distributions/app.tar 
if [ -f $APP ]; then
  cp $APP .
  docker build -t gateway .
else
  echo "Please build the app with gradle first!" 
fi
