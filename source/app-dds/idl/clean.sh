for x in `cat .gitignore`
do 
  rm -rf $x
done
