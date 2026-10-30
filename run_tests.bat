@echo off
chcp 65001 > nul
echo ================================================================================
echo    TRAFFICMAS — EXECUÇÃO DAS SUITES DE TESTES AUTOMATIZADOS (FASES 1 A 6.1)
echo ================================================================================

if not exist bin mkdir bin

echo [1/13] A compilar fontes e testes...
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/ml/*.java src/metrics/*.java src/experiments/*.java src/test/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha na compilação dos testes!
    pause
    exit /b %ERRORLEVEL%
)

echo [2/13] A executar suite de testes de segurança (Fase 1)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SafetyTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 1 falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [3/13] A executar suite de testes dos veículos (Fase 2)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.VehicleTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 2 falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [4/13] A executar suite de testes de coordenação multiagente (Fase 3)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.CoordinationTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 3 falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [5/13] A executar suite de validação do dataset de ML (Fase 4 - Etapa 1)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.DatasetValidationTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Validação do Dataset de ML falhou!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [6/13] A executar suite de testes dos modelos de ML (Fase 4 - Etapa 2)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.MachineLearningModelTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes dos Modelos de ML falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [7/13] A executar suite de testes de seleção e persistência (Fase 4 - Etapa 3)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.ModelSelectionAndPersistenceTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes de Seleção e Persistência falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [8/13] A executar suite de integração ML-Agente (Fase 4 - Etapa 4)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.MLAgentIntegrationTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes de Integração ML-Agente falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [9/13] A executar suite de métricas e exportação (Fase 5 - Etapa 1)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.TrafficMetricsTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes de Métricas e Exportação falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [10/13] A executar teste de integração controlada de métricas (Fase 5 - Etapa 1)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.ControlledSimulationIntegrationTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Teste de Integração Controlada de Metricas falhou!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [11/13] A executar suite de validação do SimulationClock (Fase 5 - Checkpoint B)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SimulationClockValidationTest

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes de Validação do SimulationClock falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [12/13] A executar suite de infraestrutura experimental e piloto (Fase 5 - Etapa 2)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.TrafficExperimentTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes de Infraestrutura Experimental falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [13/14] A executar suite de validação da Fase 6.1 (Malha 3×3, A*, Manhattan, 73 Agentes)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase61ValidationTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 6.1 falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [14/16] A executar suite de validação da Fase 6.2 (Gestão Interativa 50 Veículos e Controlo Manual Seguro)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase62ValidationTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 6.2 falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [15/16] A executar suite de autonomia dos veículos (Fase 6.2-D)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.VehicleAutonomyTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes de Autonomia dos Veículos (Fase 6.2-D) falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [16/16] A executar suite de validação da Fase 6.2-C (Interface Responsiva, Zoom, Seleção e A*)...
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase62CValidationTestSuite

if %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Testes da Fase 6.2-C falharam!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ================================================================================
echo    TODAS AS 15 SUITES DE TESTES (199 TESTES - FASES 1 A 6.2-C) FORAM CONCLUÍDAS!
echo ================================================================================
pause
