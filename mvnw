#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

set -e

MAVEN_WRAPPER_JAR=".mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES=".mvn/wrapper/maven-wrapper.properties"

if [ -r "$MAVEN_WRAPPER_PROPERTIES" ]; then
  while IFS='=' read -r key value; do
    case "$key" in
      wrapperUrl) WRAPPER_URL="$value" ;;
    esac
  done < "$MAVEN_WRAPPER_PROPERTIES"
fi

if [ ! -r "$MAVEN_WRAPPER_JAR" ]; then
  if [ -z "$WRAPPER_URL" ]; then
    WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
  fi
  echo "Downloading Maven Wrapper $WRAPPER_URL ..."
  mkdir -p .mvn/wrapper
  if command -v curl >/dev/null 2>&1; then
    curl -fLo "$MAVEN_WRAPPER_JAR" "$WRAPPER_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$MAVEN_WRAPPER_JAR" "$WRAPPER_URL"
  else
    echo "curl or wget is required to download the Maven Wrapper." >&2
    exit 1
  fi
fi

if [ -z "$JAVA_HOME" ]; then
  JAVA_EXE="java"
else
  JAVA_EXE="$JAVA_HOME/bin/java"
fi

exec "$JAVA_EXE" -Dmaven.multiModuleProjectDirectory="$PWD" -classpath "$MAVEN_WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
