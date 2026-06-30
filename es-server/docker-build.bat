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
REM   Build local single-arch image lxw13000/sano-es-server:latest
REM
REM With --push:
REM   Build and push linux/amd64 + linux/arm64 image by docker buildx
REM ============================================================

set IMAGE_NAME=lxw13000/sano-es-server
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
set LATEST_IMAGE=%IMAGE_NAME%:latest

echo.
echo [INFO] Image: %FULL_IMAGE%
if "%IMAGE_TAG%"=="latest" (
  echo [INFO] Latest: %LATEST_IMAGE%
) else (
  echo [INFO] Latest alias: %LATEST_IMAGE%
)
echo.

echo [INFO] Build Spring Boot jar by local Maven...
call mvn -q -DskipTests clean package
if errorlevel 1 (
  echo.
  echo [ERROR] Maven package failed.
  exit /b 1
)

if "%PUSH_FLAG%"=="--push" (
  echo [INFO] Build multi-arch image and push to Docker Hub...
  if "%IMAGE_TAG%"=="latest" (
    docker buildx build --platform linux/amd64,linux/arm64 -t %FULL_IMAGE% --push .
  ) else (
    docker buildx build --platform linux/amd64,linux/arm64 -t %FULL_IMAGE% -t %LATEST_IMAGE% --push .
  )
) else (
  echo [INFO] Build local image...
  if "%IMAGE_TAG%"=="latest" (
    docker build -t %FULL_IMAGE% .
  ) else (
    docker build -t %FULL_IMAGE% -t %LATEST_IMAGE% .
  )
)

if errorlevel 1 (
  echo.
  echo [ERROR] Docker build failed.
  exit /b 1
)

echo.
echo [INFO] Docker build success: %FULL_IMAGE%
if not "%IMAGE_TAG%"=="latest" (
  echo [INFO] Docker latest updated: %LATEST_IMAGE%
)
endlocal
