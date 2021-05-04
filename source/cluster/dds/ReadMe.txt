Download and build OpenDDS (https://opendds.org) with Java option:
  > ./configure --java
  > make

Be sure that you have set the OpenDDS environment variables ("source setenv.sh" in your OpenDDS directory)

Go to the idl directory and build your IDL (see readme.txt)

Use JVM option "-Djava.library.path=./lib:$DDS_ROOT/lib" to start the DDS app.
  e.g. -Djava.library.path=./lib:$DDS_ROOT/lib

Set environment variables so that the OpenDDS dll's can be found at runtime
  LD_LIBRARY_PATH=$DDS_ROOT/lib:$DSS_ROOT/ACE_wrappers/lib # Linux
  DYLD_LIBRARY_PATH=$DDS_ROOT/lib:$DSS_ROOT/ACE_wrappers/lib  # Mac

  On Windows add the paths to the system PATH variable

  Mac: .zshrc 
    export DYLD_LIBRARY_PATH=/Users/vogler/Workspace/OpenDDS-3.16/lib:/Users/vogler/Workspace/OpenDDS-3.16/ACE_wrappers/lib


