@echo off
rem Double-click this to set up the PC for sharing USB devices from Starwire. It runs setup-usb.ps1
rem without changing PowerShell's script policy, and that script asks for administrator rights.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-usb.ps1" %*
