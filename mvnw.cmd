@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_PSMODULEP_SAVE=%PSModulePath%
@SET PSModulePath=
@FOR /F "usebackq tokens=1* delims==" %%A IN (".mvn\wrapper\maven-wrapper.properties") DO @(
  @IF "%%A"=="wrapperUrl" (SET WRAPPER_URL=%%B)
)
@SET PSModulePath=%__MVNW_PSMODULEP_SAVE%

@SET MAVEN_WRAPPER_JAR=.mvn\wrapper\maven-wrapper.jar

@IF NOT EXIST "%MAVEN_WRAPPER_JAR%" (
  echo Downloading Maven Wrapper %WRAPPER_URL% ...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '.mvn/wrapper' | Out-Null; Invoke-WebRequest -Uri %WRAPPER_URL% -OutFile %MAVEN_WRAPPER_JAR%"
  @IF ERRORLEVEL 1 (
    echo Failed to download Maven Wrapper jar. >&2
    exit /b 1
  )
)

@SET JAVA_EXE=java
@IF DEFINED JAVA_HOME SET JAVA_EXE=%JAVA_HOME%\bin\java.exe

"%JAVA_EXE%" -Dmaven.multiModuleProjectDirectory="%CD%" -classpath "%MAVEN_WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
