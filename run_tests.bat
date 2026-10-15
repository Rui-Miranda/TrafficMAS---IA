@echo off
chcp 65001 > nul
echo ================================================================================
echo    TRAFFICMAS — EXECUÇÃO DAS SUITES DE TESTES AUTOMATIZADOS (FASES 1 A 4)
echo ================================================================================

if not exist bin mkdir bin

echo [1/8] A compilar fontes e testes...
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/ml/*.java src/test/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha na compilação dos testes!
    pause
    exit /b %ERRORLEVEL%
)

echo [2/8] A executar suite de testes de segurança (Fase 1)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SafetyTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 1 falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [3/8] A executar suite de testes dos veículos (Fase 2)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.VehicleTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 2 falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [4/8] A executar suite de testes de coordenação multiagente (Fase 3)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.CoordinationTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 3 falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [5/8] A executar suite de validação do dataset de ML (Fase 4 - Etapa 1)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.DatasetValidationTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Validação do Dataset de ML falhou!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [6/8] A executar suite de testes dos modelos de ML (Fase 4 - Etapa 2)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.MachineLearningModelTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes dos Modelos de ML falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [7/8] A executar suite de testes de seleção e persistência (Fase 4 - Etapa 3)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.ModelSelectionAndPersistenceTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes de Seleção e Persistência falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [8/8] A executar suite de integração ML-Agente (Fase 4 - Etapa 4)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.MLAgentIntegrationTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes de Integração ML-Agente falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ================================================================================
echo    TODAS AS SUITES DE TESTES (74 TESTES - FASES 1 A 4) FORAM CONCLUÍDAS!
echo ================================================================================
pause
