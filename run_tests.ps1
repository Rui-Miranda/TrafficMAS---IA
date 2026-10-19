# Script PowerShell para Execucao das Suites de Testes do TrafficMAS (Fases 1, 2, 3 e 4)
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   TRAFFICMAS -- EXECUCAO DAS SUITES DE TESTES AUTOMATIZADOS (FASES 1 A 4)" -ForegroundColor Cyan
Write-Host "================================================================================`n" -ForegroundColor Cyan

if (-not (Test-Path "bin")) {
    New-Item -ItemType Directory -Path "bin" | Out-Null
}

Write-Host "[1/12] A compilar fontes e testes..." -ForegroundColor Yellow
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/ml/*.java src/metrics/*.java src/experiments/*.java src/test/*.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Falha durante a compilacao dos testes!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/12] A executar suite de testes de seguranca (Fase 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SafetyTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 1 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[3/12] A executar suite de testes dos veiculos (Fase 2)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.VehicleTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 2 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[4/12] A executar suite de testes de coordenacao (Fase 3)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.CoordinationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 3 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[5/12] A executar suite de validacao do dataset de ML (Fase 4 - Etapa 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.DatasetValidationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Validacao do Dataset de ML falhou!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[6/12] A executar suite de testes dos modelos de ML (Fase 4 - Etapa 2)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.MachineLearningModelTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes dos Modelos de ML falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[7/12] A executar suite de testes de selecao e persistencia (Fase 4 - Etapa 3)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.ModelSelectionAndPersistenceTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Selecao e Persistencia falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[8/12] A executar suite de integracao ML-Agente (Fase 4 - Etapa 4)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.MLAgentIntegrationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Integracao ML-Agente falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[9/12] A executar suite de metricas e exportacao (Fase 5 - Etapa 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.TrafficMetricsTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Metricas e Exportacao falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[10/12] A executar teste de integracao controlada de metricas (Fase 5 - Etapa 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.ControlledSimulationIntegrationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Teste de Integracao Controlada de Metricas falhou!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[11/12] A executar suite de validacao do SimulationClock (Fase 5 - Checkpoint B)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SimulationClockValidationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Validacao do SimulationClock falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[12/12] A executar suite de infraestrutura experimental e piloto (Fase 5 - Etapa 2)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.TrafficExperimentTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Infraestrutura Experimental falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   TODAS AS 11 SUITES DE TESTES (112 TESTES - FASES 1 A 5) FORAM CONCLUIDAS!" -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Cyan

