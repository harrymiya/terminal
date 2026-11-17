@rem
@rem Copyright 2026 Remote Dev Terminal
@rem
@echo off
set SCRIPT_DIR=%~dp0
"%SCRIPT_DIR%android\gradlew.bat" -p "%SCRIPT_DIR%android" %*
