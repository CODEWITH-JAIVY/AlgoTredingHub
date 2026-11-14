@echo off
setlocal
for %%I in ("%~dp0.") do set "MAVEN_PROJECTBASEDIR=%%~fI"
if not defined JAVA_HOME (
  echo JAVA_HOME must point to a Java 21 installation. The Maven Wrapper does not install Java.
  exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo JAVA_HOME does not contain bin\java.exe.
  exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -classpath "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
exit /b %ERRORLEVEL%
