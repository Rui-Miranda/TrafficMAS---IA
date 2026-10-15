@echo off
chcp 65001 > nul
echo ================================================================================
echo    TRAFFICMAS — INTELLIGENT MULTI-AGENT TRAFFIC CONTROL SYSTEM (ISPGAYA)
echo    Compilacao e Execucao da Plataforma Multiagente JADE
echo ================================================================================

if not exist bin mkdir bin

echo [1/2] A compilar fontes Java...
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/ml/*.java src/metrics/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha na compilação do código Java!
    pause
    exit /b %ERRORLEVEL%
)

echo [2/2] A iniciar plataforma JADE e simulador visual...
java -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" Main

pause
