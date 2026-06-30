@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM es-server Docker build script for Windows
REM
REM Usage:
REM   docker-build.bat
REM   docker-build.bat v1.0.0
REM   docker-build.bat v1.0.0 --push
REM
REM Default:
REM   Build local single-arch image lxw13000/sano/es-server:latest
REM
REM With --push:
REM   Build and push linux/amd64 + linux/arm64 image by docker buildx
REM ============================================================

set IMAGE_NAME=lxw13000/sano/es-server
set IMAGE_TAG=%~1
set PUSH_FLAG=%~2

if "%IMAGE_TAG%"=="" (
  set IMAGE_TAG=latest
)

if "%IMAGE_TAG%"=="--push" (
  set IMAGE_TAG=latest
  set PUSH_FLAG=--push
)

set FULL_IMAGE=%IMAGE_NAME%:%IMAGE_TAG%

echo.
echo [INFO] Image: %FULL_IMAGE%
echo.

echo [INFO] Build Spring Boot jar by local Maven...
call mvn -q -DskipTests package
if errorlevel 1 (
  echo.
  echo [ERROR] Maven package failed.
  exit /b 1
)

if "%PUSH_FLAG%"=="--push" (
  echo [INFO] Build multi-arch image and push to Docker Hub...
  docker buildx build --platform linux/amd64,linux/arm64 -t %FULL_IMAGE% --push .
) else (
  echo [INFO] Build local image...
  docker build -t %FULL_IMAGE% .
)

if errorlevel 1 (
  echo.
  echo [ERROR] Docker build failed.
  exit /b 1
)

echo.
echo [INFO] Docker build success: %FULL_IMAGE%
endlocal
