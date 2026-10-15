# Script PowerShell de Compilação e Execução do TrafficMAS
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "   TRAFFICMAS — INTELLIGENT MULTI-AGENT TRAFFIC CONTROL SYSTEM (ISPGAYA)" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

if (-not (Test-Path "bin")) {
    New-Item -ItemType Directory -Path "bin" | Out-Null
}

Write-Host "[1/2] A compilar fontes Java..." -ForegroundColor Yellow
javac -encoding UTF-8 -cp ".;lib/jade.jar;lib/commons-codec-1.3.jar" -d bin src/Main.java src/agents/*.java src/environment/*.java src/ml/*.java src/metrics/*.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Falha durante a compilação do projeto!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/2] A executar o ecossistema JADE e a interface urbana..." -ForegroundColor Green
java -cp "bin;lib/jade.jar;lib/commons-codec-1.3.jar" Main
