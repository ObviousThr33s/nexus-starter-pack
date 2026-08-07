@echo off
REM Builds sage.jar into the project folder, one level up.
REM
REM --server=false skips Bloop. Bloop's compile server does not start against
REM the JDK 24 on this box, and a background compile daemon buys little for a
REM project this size - the whole thing compiles in a few seconds.
REM
REM Once scala3-compiler is in the Coursier cache this needs no network, which
REM is the point: the program has no dependencies beyond scala3-library, so a
REM machine that cannot reach Maven can still rebuild it.

setlocal

REM %~dp0 ends with a backslash, so "%~dp0" would end in \" - which escapes the
REM closing quote and hands the next argument to the wrong place. Strip it.
set "HERE=%~dp0"
set "HERE=%HERE:~0,-1%"
set "OUT=%HERE%\..\sage.jar"

REM Find scala-cli. It lands in Program Files but a shell opened before the
REM install will not have the updated PATH, which is a confusing way to be told
REM "not recognized as an internal or external command".
set "SCALA_CLI=scala-cli"
where scala-cli >nul 2>&1
if errorlevel 1 (
    if exist "C:\Program Files\scala-cli-x86_64-pc-win32\scala-cli.exe" (
        set "SCALA_CLI=C:\Program Files\scala-cli-x86_64-pc-win32\scala-cli.exe"
    ) else (
        echo scala-cli not found on PATH or in Program Files.
        echo Install it with:  winget install --id VirtusLab.ScalaCLI
        exit /b 1
    )
)

echo Running the self-test before packaging...
call "%SCALA_CLI%" run "%HERE%" --server=false -- --self-test
if errorlevel 1 (
    echo.
    echo Self-test FAILED - not packaging. Fix it first.
    exit /b 1
)

echo.
echo Packaging to %OUT%
call "%SCALA_CLI%" --power package "%HERE%" --server=false --assembly -o "%OUT%" --force
if errorlevel 1 exit /b 1

echo.
echo Built. Run it with:
echo     sage-scala.cmd --self-test
echo     sage-scala.cmd --doctor
echo     sage-scala.cmd --firewall http://192.168.0.1
