#!/bin/bash

build() {
  app=${1:-app}
  sub=${2:-.}
  ver=${3:-latest}

  echo $app $sub $ver

  if [ $sub = "." ]; then
    name="rocworks/automation-gateway:$ver"
    app_with_path=../source/$app/build/distributions/$app.tar 
  else
    app=$app-$sub	    
    name="rocworks/automation-gateway:$ver-$sub"
    app_with_path=../source/$app/build/distributions/$app.tar 
  fi	   

  if [ -f $app_with_path ]; then
    echo $app
    cp $app_with_path ./app.tar
    docker build --build-arg APP_NAME=$app -t $name .
    rm ./app.tar
  else
    echo "Please build the app ${app_with_path} with gradle first!" 
  fi
}

# Get the current branch name
branch=$(git rev-parse --abbrev-ref HEAD)

# Check if the branch is not "master"
if [ "$branch" != "master" ]; then
  branch_name="-$branch"
else
  branch_name=""
fi

ver=${2:-`cat version.txt`}$branch_name

echo $ver

#build app . $ver
#build app plc4x $ver
