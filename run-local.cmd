@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
if not defined FOS_DB_PASSWORD if not defined FOS_LOCAL_DB_PASSWORD (
  echo Set FOS_DB_PASSWORD to your local PostgreSQL password first.
  exit /b 1
)
if not defined FOS_LOCAL_PASSWORD (
  echo Set FOS_LOCAL_PASSWORD to at least 16 characters first.
  exit /b 1
)
if not defined FOS_DB_URL set "FOS_DB_URL=jdbc:postgresql://localhost:5432/fos"
if not defined FOS_DB_USER set "FOS_DB_USER=fos"
set "SPRING_PROFILES_ACTIVE=local"
call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
exit /b %errorlevel%
