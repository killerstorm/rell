@ECHO OFF

SETLOCAL

SET JAR_FILE=_file_not_found_
FOR /F %%F IN ('DIR /ON /B "%~dp0lib"\rell-*.*.*.jar') DO SET JAR_FILE=%%F

SET CP=%~dp0lib/%JAR_FILE%;%~dp0lib/extra/*

IF NOT DEFINED RELL_JAVA SET RELL_JAVA=java
"%RELL_JAVA%" -cp "%CP%" "%MAIN_CLASS%" %*
