param(
  [string]$TokenFile,
  [string]$RepoName = "sales-order-system",
  [string]$LocalDir = "C:/Users/97429/WorkBuddy/2026-11-10-09-48-18/sales-order-system",
  [string]$Branch = "main",
  [string]$CommitMessage = "feat: sales-order-system initial commit",
  [string]$Description = "Sales order full-process collaboration system - zero-dependency JDK 25 project"
)

$ErrorActionPreference = "Stop"
if (-not $TokenFile -or -not (Test-Path $TokenFile)) { Write-Error "TokenFile not found"; exit 1 }
$token = [System.IO.File]::ReadAllText($TokenFile).Trim()

$headers = @{
  Authorization = "Bearer $token"
  "User-Agent"  = "workbuddy-push"
  "Accept"      = "application/vnd.github+json"
  "X-GitHub-Api-Version" = "2022-11-28"
}
$api = "https://api.github.com"

function Invoke-GitHub {
  param($Uri, $Method="GET", $Body=$null)
  for ($i=1; $i -le 4; $i++) {
    try {
      $params = @{ Uri=$Uri; Method=$Method; Headers=$headers; TimeoutSec=90 }
      if ($Body) { $params.Body = [System.Text.Encoding]::UTF8.GetBytes($Body); $params.ContentType = "application/json" }
      return Invoke-RestMethod @params
    } catch {
      $code = $null
      if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
      Write-Host "  attempt $i failed (HTTP $code): $($_.Exception.Message)"
      if ($i -eq 4) { throw }
      Start-Sleep -Seconds 2
    }
  }
}

# 1. whoami
$me = Invoke-GitHub "$api/user"
$owner = $me.login
Write-Host "Authenticated as: $owner"

# 2. check / create repo
$exists = $false
try { $repoInfo = Invoke-GitHub "$api/repos/$owner/$RepoName"; $exists = $true }
catch { if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) { $exists = $false } else { throw } }

if (-not $exists) {
  Write-Host "Creating repo $owner/$RepoName (public)..."
  $createBody = @{ name=$RepoName; private=$false; auto_init=$false; description=$Description } | ConvertTo-Json
  Invoke-GitHub "$api/user/repos" -Method Post -Body $createBody | Out-Null
  Write-Host "Repo created."
  Start-Sleep -Seconds 2
} else {
  Write-Host "Repo $owner/$RepoName already exists."
}

# 3. base commit (may be absent on empty repo)
$baseCommitSha = $null
$baseTreeSha = $null
try {
  $ref = Invoke-GitHub "$api/repos/$owner/$RepoName/git/ref/heads/$Branch"
  $baseCommitSha = $ref.object.sha
  $baseCommit = Invoke-GitHub "$api/repos/$owner/$RepoName/git/commits/$baseCommitSha"
  $baseTreeSha = $baseCommit.tree.sha
  Write-Host "Base commit: $baseCommitSha"
} catch {
  $code = $null
  if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
  if ($code -eq 404 -or $code -eq 409) {
    Write-Host "Empty repo (no branch yet) - bootstrapping initial branch..."
    $b64 = [System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("# bootstrap`n"))
    $bootBody = @{ message="bootstrap: create initial branch"; content=$b64; branch=$Branch } | ConvertTo-Json
    Invoke-GitHub "$api/repos/$owner/$RepoName/contents/.gitkeep" -Method Put -Body $bootBody | Out-Null
    Start-Sleep -Seconds 1
    $ref = Invoke-GitHub "$api/repos/$owner/$RepoName/git/ref/heads/$Branch"
    $baseCommitSha = $ref.object.sha
    $baseCommit = Invoke-GitHub "$api/repos/$owner/$RepoName/git/commits/$baseCommitSha"
    $baseTreeSha = $baseCommit.tree.sha
    Write-Host "Bootstrapped base commit: $baseCommitSha"
  } else { throw }
}

# 4. gather files, respecting a small inline ignore list
function Test-Ignored($relPath) {
  $parts = $relPath -split '/'
  if ($parts -contains 'build') { return $true }
  if ($relPath -eq 'server.log' -or $relPath.EndsWith('.log')) { return $true }
  if ($relPath -like '.workbuddy/*') { return $true }
  if ($relPath -like 'data/*') { return $true }
  return $false
}

$files = @()
Get-ChildItem -Path $LocalDir -Recurse -File | ForEach-Object {
  $rel = $_.FullName.Substring($LocalDir.Length).TrimStart('\','/') -replace '\\','/'
  if (-not (Test-Ignored $rel)) { $files += $_ }
}
Write-Host "Found $($files.Count) files to push."

# 5. create blobs (always base64: pure ASCII, no JSON escaping issues)
$tree = @()
foreach ($f in $files) {
  $rel = $f.FullName.Substring($LocalDir.Length).TrimStart('\','/') -replace '\\','/'
  $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
  $b64 = [System.Convert]::ToBase64String($bytes)
  $blob = Invoke-GitHub "$api/repos/$owner/$RepoName/git/blobs" -Method Post -Body (@{content=$b64; encoding='base64'} | ConvertTo-Json)
  $tree += @{ path=$rel; mode='100644'; type='blob'; sha=$blob.sha }
  Write-Host "  blob $rel -> $($blob.sha.Substring(0,7))"
}

# 6. create tree
$treeBody = @{ tree = $tree }
if ($baseTreeSha) { $treeBody.base_tree = $baseTreeSha }
$newTree = Invoke-GitHub "$api/repos/$owner/$RepoName/git/trees" -Method Post -Body ($treeBody | ConvertTo-Json -Depth 5)

# 7. create commit
$commitBody = @{ message = $CommitMessage; tree = $newTree.sha }
if ($baseCommitSha) { $commitBody.parents = @($baseCommitSha) }
$commit = Invoke-GitHub "$api/repos/$owner/$RepoName/git/commits" -Method Post -Body ($commitBody | ConvertTo-Json -Depth 5)

# 8. create or update ref
if ($baseCommitSha) {
  Invoke-GitHub "$api/repos/$owner/$RepoName/git/refs/heads/$Branch" -Method Patch -Body (@{sha=$commit.sha} | ConvertTo-Json) | Out-Null
  Write-Host "Updated $Branch -> $($commit.sha.Substring(0,7))"
} else {
  Invoke-GitHub "$api/repos/$owner/$RepoName/git/refs" -Method Post -Body (@{ref="refs/heads/$Branch"; sha=$commit.sha} | ConvertTo-Json) | Out-Null
  Write-Host "Created $Branch -> $($commit.sha.Substring(0,7))"
}

Write-Host "DONE: https://github.com/$owner/$RepoName"
