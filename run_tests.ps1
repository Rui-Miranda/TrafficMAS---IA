# Script PowerShell para Execucao das Suites de Testes do TrafficMAS (Fases 1, 2 e 3)
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   TRAFFICMAS -- EXECUCAO DAS SUITES DE TESTES AUTOMATIZADOS (FASES 1, 2 E 3)" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

if (-not (Test-Path "bin")) {
    New-Item -ItemType Directory -Path "bin" | Out-Null
}

Write-Host "[1/5] A compilar fontes e testes..." -ForegroundColor Yellow
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/ml/*.java src/test/*.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Falha durante a compilacao dos testes!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/5] A executar suite de testes de seguranca (Fase 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SafetyTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 1 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[3/5] A executar suite de testes dos veiculos (Fase 2)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.VehicleTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 2 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[4/5] A executar suite de testes de coordenacao (Fase 3)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.CoordinationTestSuite

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Testes da Fase 3 falharam!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[5/5] A executar suite de validacao do dataset de ML (Fase 4 - Etapa 1)..." -ForegroundColor Green
java "-Djava.awt.headless=true" -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.DatasetValidationTest

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Validacao do Dataset de ML falhou!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   TODAS AS SUITES DE TESTES (FASES 1, 2, 3 E 4) CONCLUIDAS COM SUCESSO!" -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Cyan
