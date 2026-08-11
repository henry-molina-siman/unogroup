# unogroup-app/scripts/deploy-local.ps1
#
# Builds the image with a unique tag on every run and deploys it to the
# local cluster (namespace ensambles). A fixed tag like `unogroup-app:local`
# combined with imagePullPolicy: IfNotPresent lets the node keep serving a
# stale image after a rebuild, because kubelet only checks whether a tag is
# already present, not whether it changed. A fresh tag per build sidesteps
# that entirely: the node has never seen it, so it always loads what you
# just built.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $secretPatch = "k8s/overlays/local/secret-patch.yaml"
    if (-not (Test-Path $secretPatch)) {
        Copy-Item "$secretPatch.example" $secretPatch
        Write-Warning "$secretPatch didn't exist — created from the .example template with placeholder credentials. Edit it with real Solution One credentials, then re-run this script."
        exit 1
    }

    $tag = Get-Date -Format "yyyyMMddHHmmss"
    $image = "unogroup-app:$tag"

    docker build -t $image .
    if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

    kubectl apply -k k8s/overlays/local
    if ($LASTEXITCODE -ne 0) { throw "kubectl apply -k failed" }

    kubectl -n ensambles set image deployment/unogroup-app "unogroup-app=$image"
    if ($LASTEXITCODE -ne 0) { throw "kubectl set image failed" }

    kubectl -n ensambles rollout status deployment/unogroup-app --timeout=180s
}
finally {
    Pop-Location
}
