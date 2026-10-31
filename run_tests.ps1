# Script PowerShell para Execucao das Suites de Testes do TrafficMAS (Fases 1 a 6.5-A)
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   TRAFFICMAS -- EXECUCAO DAS SUITES DE TESTES AUTOMATIZADOS (FASES 1 A 6.5-A)" -ForegroundColor Cyan
Write-Host "================================================================================`n" -ForegroundColor Cyan

if (-not (Test-Path "bin")) {
    New-Item -ItemType Directory -Path "bin" | Out-Null
}

Write-Host "[1/19] A compilar fontes e testes..." -ForegroundColor Yellow
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/ml/*.java src/metrics/*.java src/experiments/*.java src/test/*.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Falha durante a compilacao dos testes!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/15] A executar suite de testes de seguranca (Fase 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SafetyTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 1 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[3/15] A executar suite de testes dos veiculos (Fase 2)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.VehicleTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 2 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[4/15] A executar suite de testes de coordenacao (Fase 3)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.CoordinationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 3 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[5/15] A executar suite de validacao do dataset de ML (Fase 4 - Etapa 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.DatasetValidationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Validacao do Dataset de ML falhou!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[6/15] A executar suite de testes dos modelos de ML (Fase 4 - Etapa 2)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.MachineLearningModelTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes dos Modelos de ML falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[7/15] A executar suite de testes de selecao e persistencia (Fase 4 - Etapa 3)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.ModelSelectionAndPersistenceTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Selecao e Persistencia falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[8/15] A executar suite de integracao ML-Agente (Fase 4 - Etapa 4)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.MLAgentIntegrationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Integracao ML-Agente falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[9/15] A executar suite de metricas e exportacao (Fase 5 - Etapa 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.TrafficMetricsTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Metricas e Exportacao falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[10/15] A executar teste de integracao controlada de metricas (Fase 5 - Etapa 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.ControlledSimulationIntegrationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Teste de Integracao Controlada de Metricas falhou!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Restaura run_summary.csv caso o ControlledSimulationIntegrationTest tenha atualizado o timestamp realDurationSeconds
git checkout -- results/run_summary.csv results/intersection_metrics.csv 2>$null

Write-Host ""
Write-Host "[11/15] A executar suite de validacao do SimulationClock (Fase 5 - Checkpoint B)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SimulationClockValidationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Validacao do SimulationClock falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[12/15] A executar suite de infraestrutura experimental e piloto (Fase 5 - Etapa 2)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.TrafficExperimentTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Infraestrutura Experimental falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[13/15] A executar suite de validacao da Fase 6.1 (Malha 3x3, A*, Manhattan, 73 Agentes)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase61ValidationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 6.1 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[14/15] A executar suite de validacao da Fase 6.2 (Gestao Interativa 50 Veiculos e Controlo Manual Seguro)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase62ValidationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 6.2 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[15/16] A executar suite de autonomia operacional dos agentes veiculo (Fase 6.2-D)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.VehicleAutonomyTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes de Autonomia Operacional dos Veiculos (Fase 6.2-D) falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[16/17] A executar suite de validacao da Fase 6.2-C (Interface Responsiva, Zoom, Selecao por Clique, A* e Autonomia)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase62CValidationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 6.2-C falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[17/18] A executar suite de validacao da Fase 6.3 (Cedencia Fisica a Emergencias e Cooperacao Multiagente)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase63YieldValidationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 6.3 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[18/19] A executar suite de validacao da Fase 6.4 (Meteorologia Dinamica, Histerese do Piso e Adaptacao Multiagente)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase64WeatherValidationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 6.4 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[19/19] A executar suite de validacao da Fase 6.5-A (Coordenacao Real de 9 Cruzamentos, Agendamento Fisico e Ondas Verdes 3x3)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.Phase65ACoordinationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 6.5-A falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   TODAS AS 18 SUITES DE TESTES (271 TESTES - FASES 1 A 6.5-A) FORAM CONCLUIDAS!" -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Cyan
