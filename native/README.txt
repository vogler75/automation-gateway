# Add Native-Image to GraalVM
gu install native-image

######################
# Linux
######################
sudo apt-get install build-essential libz-dev zlib1g-dev
sudo yum install zlib-devel 

######################
# Windows
######################
Download and Install Visual Stdio Build Tools:
https://visualstudio.microsoft.com/thank-you-downloading-visual-studio/?sku=BuildTools&rel=16

Check the 
* Desktop development with C++ box in the main window. 
* On the right side under Installation Details, choose Windows 10 SDK
Click the Install button.

After the installation completes, reboot your system.

On Windows, the native-image builder will only work when it’s executed from the x64 Native Tools Command Prompt
> "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

######################
# Build
######################
> update.sh  # build and copy java distribution of app to source and gets classpath
> run.sh     # start the java distribution with agento to collect GraalVM configs
> build.sh   # build native image with GraalVM

######################
# Notes
######################

Lin: tar xf ../source/app/build/distributions/app.tar -C source
Win: powershell -command "Expand-Archive ../source/app/build/distributions/app.zip source"

Set JAVA_OPTS="-agentlib:native-image-agent=config-merge-dir=config" to enable the agent.
> set JAVA_OPTS=-agentlib:native-image-agent=config-merge-dir=config
> export JAVA_OPTS=-agentlib:native-image-agent=config-merge-dir=config

Copy the CLASSPATH from app\bin\app to the build.sh script!