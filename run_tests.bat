@echo off
chcp 65001 > nul
echo ================================================================================
echo    TRAFFICMAS — EXECUÇÃO DA SUITE DE TESTES AUTOMATIZADOS (FASE 1)
echo ================================================================================

if not exist bin mkdir bin

echo [1/2] A compilar fontes e testes...
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/test/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha na compilação dos testes!
    pause
    exit /b %ERRORLEVEL%
)

echo [2/2] A executar suite de testes de segurança...
java -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SafetyTestSuite

pause
