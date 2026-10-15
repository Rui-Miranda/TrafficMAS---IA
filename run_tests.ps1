# Script PowerShell para Execução da Suite de Testes do TrafficMAS
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   TRAFFICMAS — EXECUÇÃO DA SUITE DE TESTES AUTOMATIZADOS (FASE 1)" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

if (-not (Test-Path "bin")) {
    New-Item -ItemType Directory -Path "bin" | Out-Null
}

Write-Host "[1/2] A compilar fontes e testes..." -ForegroundColor Yellow
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/test/*.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Falha durante a compilação dos testes!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/2] A executar suite de testes de segurança..." -ForegroundColor Green
java -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" test.SafetyTestSuite
