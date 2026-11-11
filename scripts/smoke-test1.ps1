<#
  LedgerLine end-to-end smoke test.
  Runs every endpoint and the main attacks, prints PASS/FAIL for each check.
  Safe to run many times: every run creates fresh users, accounts and idempotency keys.

  Usage (from the repo root, with the backend running):
    powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test1.ps1
    powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test1.ps1 -BaseUrl https://your-backend.up.railway.app -SkipDbCheck
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AdminEmail = "admin@example.com",
    [string]$AdminPassword = "change-me-please-123",
    [switch]$SkipDbCheck
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)

$script:passed = 0
$script:failed = 0
$script:failures = @()
$run = Get-Date -Format "yyyyMMddHHmmss"

# ---------------------------------------------------------------- helpers

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token,
        $Body,
        [string]$RawBody,
        [string]$IdemKey
    )
    $httpMethod = New-Object System.Net.Http.HttpMethod($Method)
    $req = New-Object System.Net.Http.HttpRequestMessage($httpMethod, "$BaseUrl$Path")
    if ($Token) {
        $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $Token)
    }
    if ($IdemKey) {
        [void]$req.Headers.TryAddWithoutValidation("Idempotency-Key", $IdemKey)
    }
    $json = $null
    if ($RawBody) { $json = $RawBody }
    elseif ($null -ne $Body) { $json = $Body | ConvertTo-Json -Depth 5 -Compress }
    if ($json) {
        $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, "application/json")
    }
    $resp = $client.SendAsync($req).Result
    $text = $resp.Content.ReadAsStringAsync().Result
    $parsed = $null
    if ($text -and $text.Trim().Length -gt 0) {
        try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null }
    }
    [pscustomobject]@{
        Status   = [int]$resp.StatusCode
        Json     = $parsed
        Text     = $text
        Replayed = $resp.Headers.Contains("Idempotent-Replayed")
    }
}

function Short($r) {
    $t = $r.Text
    if ($t -and $t.Length -gt 300) { $t = $t.Substring(0, 300) + "..." }
    return "status=$($r.Status) body=$t"
}

function Check {
    param([string]$Step, [bool]$Condition, [string]$Detail = "")
    if ($Condition) {
        $script:passed++
        Write-Host "PASS  $Step" -ForegroundColor Green
    } else {
        $script:failed++
        $script:failures += "$Step  ->  $Detail"
        Write-Host "FAIL  $Step  ->  $Detail" -ForegroundColor Red
    }
}

function Get-Prop($obj, [string[]]$names) {
    if ($null -eq $obj) { return $null }
    foreach ($n in $names) {
        if ($obj.PSObject.Properties.Name -contains $n) { return $obj.$n }
    }
    return $null
}

function Login($email, $password) {
    $r = Invoke-Api POST "/api/auth/login" -Body @{ email = $email; password = $password }
    if ($r.Status -ne 200 -or -not $r.Json.token) { throw "Login failed for $email : $(Short $r)" }
    return $r.Json.token
}

function Key($name) { return "$name-$run" }

$aliceEmail  = "alice-$run@test.local"
$bobEmail    = "bob-$run@test.local"
$audreyEmail = "audrey-$run@test.local"

Write-Host ""
Write-Host "LedgerLine smoke test against $BaseUrl  (run $run)" -ForegroundColor Cyan
Write-Host ""

# ---------------------------------------------------------------- Part 0: health

$r = Invoke-Api GET "/actuator/health"
Check "00 health is UP" ($r.Status -eq 200 -and $r.Json.status -eq "UP") (Short $r)

$r = Invoke-Api GET "/api/accounts"
Check "00 no token -> 401" ($r.Status -eq 401) (Short $r)

# ---------------------------------------------------------------- Part 1: users

$adminTok = Login $AdminEmail $AdminPassword
Check "01 admin login" ($null -ne $adminTok)

$r = Invoke-Api GET "/api/auth/me" -Token $adminTok
$adminId = $r.Json.id
Check "02 /me is ADMIN" ($r.Status -eq 200 -and $r.Json.role -eq "ADMIN") (Short $r)

$r = Invoke-Api POST "/api/admin/users" -Token $adminTok -Body @{
    email = $aliceEmail; fullName = "Alice Holder"; password = "alice-pass-123"; role = "HOLDER" }
$aliceId = $r.Json.id
Check "03 admin creates holder Alice" ($r.Status -eq 201 -and $r.Json.role -eq "HOLDER") (Short $r)

$r = Invoke-Api POST "/api/auth/register" -Body @{
    email = $bobEmail; fullName = "Bob Holder"; password = "bob-pass-123"; role = "ADMIN" }
$bobId = $r.Json.id
Check "04 register with role=ADMIN still creates HOLDER" ($r.Status -eq 201 -and $r.Json.role -eq "HOLDER") (Short $r)

$r = Invoke-Api POST "/api/admin/users" -Token $adminTok -Body @{
    email = $audreyEmail; fullName = "Audrey Auditor"; password = "audrey-pass-123"; role = "AUDITOR" }
Check "05 admin creates auditor" ($r.Status -eq 201 -and $r.Json.role -eq "AUDITOR") (Short $r)

$r = Invoke-Api GET "/api/admin/users" -Token $adminTok
Check "06 list users, no password hashes" ($r.Status -eq 200 -and $r.Text -notmatch "(?i)password") (Short $r)

$r = Invoke-Api POST "/api/auth/register" -Body @{
    email = $aliceEmail.ToUpper(); fullName = "Fake Alice"; password = "whatever-123" }
Check "07 duplicate email (uppercase) -> 409" ($r.Status -eq 409) (Short $r)

# ---------------------------------------------------------------- Part 2: accounts and onboarding

$aliceTok = Login $aliceEmail "alice-pass-123"
$r = Invoke-Api POST "/api/accounts" -Token $aliceTok -Body @{ name = "Alice Savings" }
$aliceAcc = $r.Json.id
Check "08 Alice requests account -> PENDING" ($r.Status -eq 201 -and $r.Json.status -eq "PENDING") (Short $r)

$r = Invoke-Api POST "/api/accounts/$aliceAcc/deposits" -Token $aliceTok -IdemKey (Key "alice-dep-pending") -Body @{ amountPaise = 5000 }
Check "09 deposit into PENDING account -> 409 ACCOUNT_NOT_ACTIVE" ($r.Status -eq 409) (Short $r)

$bobTok = Login $bobEmail "bob-pass-123"
$r = Invoke-Api POST "/api/accounts" -Token $bobTok -Body @{ name = "Bob Main" }
$bobAcc = $r.Json.id
Check "10 Bob requests account -> PENDING" ($r.Status -eq 201 -and $r.Json.status -eq "PENDING") (Short $r)

$r = Invoke-Api GET "/api/admin/accounts?status=PENDING" -Token $adminTok
$pendingIds = @($r.Json | ForEach-Object { $_.id })
Check "11 admin sees both PENDING accounts" ($r.Status -eq 200 -and ($pendingIds -contains $aliceAcc) -and ($pendingIds -contains $bobAcc)) (Short $r)

$r = Invoke-Api PATCH "/api/admin/accounts/$aliceAcc/status" -Token $adminTok -Body @{ status = "ACTIVE" }
Check "12a approve Alice's account" ($r.Status -eq 200 -and $r.Json.status -eq "ACTIVE") (Short $r)
$r = Invoke-Api PATCH "/api/admin/accounts/$bobAcc/status" -Token $adminTok -Body @{ status = "ACTIVE" }
Check "12b approve Bob's account" ($r.Status -eq 200 -and $r.Json.status -eq "ACTIVE") (Short $r)

$r = Invoke-Api PATCH "/api/admin/accounts/$aliceAcc/status" -Token $adminTok -Body @{ status = "PENDING" }
Check "13 invalid transition ACTIVE->PENDING -> 400" ($r.Status -eq 400) (Short $r)

$r = Invoke-Api GET "/api/accounts" -Token $adminTok
$ext = @($r.Json | Where-Object { $_.type -eq "EXTERNAL" })
$extId = $null
if ($ext.Count -gt 0) { $extId = $ext[0].id }
Check "14a admin can see the EXTERNAL account" ($null -ne $extId) (Short $r)

$r = Invoke-Api PATCH "/api/admin/accounts/$extId/status" -Token $adminTok -Body @{ status = "FROZEN" }
Check "14b changing EXTERNAL account status -> 403" ($r.Status -eq 403) (Short $r)

# ---------------------------------------------------------------- Part 3: money

$k15 = Key "admin-dep-alice"
$r = Invoke-Api POST "/api/accounts/$aliceAcc/deposits" -Token $adminTok -IdemKey $k15 -Body @{ amountPaise = 100000 }
$firstTxId = $r.Json.transactionId
Check "15 admin deposits 100000 -> 201 POSTED" ($r.Status -eq 201 -and $r.Json.status -eq "POSTED") (Short $r)

$r = Invoke-Api POST "/api/accounts/$aliceAcc/deposits" -Token $adminTok -IdemKey $k15 -Body @{ amountPaise = 100000 }
Check "16 same key replay -> same transactionId + Idempotent-Replayed header" ($r.Status -eq 201 -and $r.Json.transactionId -eq $firstTxId -and $r.Replayed) (Short $r)

$r = Invoke-Api POST "/api/accounts/$aliceAcc/deposits" -Token $adminTok -IdemKey $k15 -Body @{ amountPaise = 50000 }
Check "17 same key, different amount -> 409" ($r.Status -eq 409) (Short $r)

$r = Invoke-Api POST "/api/accounts/$aliceAcc/withdrawals" -Token $adminTok -IdemKey (Key "admin-wd-alice") -Body @{ amountPaise = 1000 }
Check "18 admin withdraws from customer -> 403" ($r.Status -eq 403) (Short $r)

$r = Invoke-Api POST "/api/accounts/$aliceAcc/withdrawals" -Token $aliceTok -IdemKey (Key "alice-wd-1") -Body @{ amountPaise = 20000 }
Check "19 Alice withdraws 20000 -> 201" ($r.Status -eq 201 -and $r.Json.status -eq "POSTED") (Short $r)

$r = Invoke-Api POST "/api/accounts/$aliceAcc/withdrawals" -Token $aliceTok -IdemKey (Key "alice-wd-2") -Body @{ amountPaise = 999999 }
Check "20 overdraw -> 422 INSUFFICIENT_FUNDS" ($r.Status -eq 422 -and $r.Json.rejectReason -eq "INSUFFICIENT_FUNDS") (Short $r)

$r = Invoke-Api POST "/api/transfers" -Token $aliceTok -IdemKey (Key "alice-tr-1") -Body @{ fromAccountId = $aliceAcc; toAccountId = $bobAcc; amountPaise = 30000 }
Check "21 Alice transfers 30000 to Bob -> 201" ($r.Status -eq 201 -and $r.Json.status -eq "POSTED") (Short $r)

$r22 = Invoke-Api POST "/api/transfers" -Token $aliceTok -IdemKey (Key "alice-tr-ext") -Body @{ fromAccountId = $aliceAcc; toAccountId = $extId; amountPaise = 100 }
Check "22 transfer to EXTERNAL -> 404" ($r22.Status -eq 404) (Short $r22)

$r23 = Invoke-Api POST "/api/transfers" -Token $aliceTok -IdemKey (Key "alice-tr-missing") -Body @{ fromAccountId = $aliceAcc; toAccountId = 999999999; amountPaise = 100 }
Check "23 transfer to missing account -> 404" ($r23.Status -eq 404) (Short $r23)
Check "23b EXTERNAL and missing destination look the same" (
    $r22.Json.rejectReason -eq $r23.Json.rejectReason -and $null -eq $r22.Json.toAccountId -and $null -eq $r23.Json.toAccountId
) "22: $($r22.Text)  23: $($r23.Text)"

$r = Invoke-Api POST "/api/transfers" -Token $aliceTok -IdemKey (Key "alice-tr-self") -Body @{ fromAccountId = $aliceAcc; toAccountId = $aliceAcc; amountPaise = 100 }
Check "24 transfer to self -> 400" ($r.Status -eq 400) (Short $r)

$r = Invoke-Api POST "/api/accounts/$aliceAcc/withdrawals" -Token $aliceTok -IdemKey (Key "alice-bad-zero") -Body @{ amountPaise = 0 }
Check "25a amount 0 -> 400" ($r.Status -eq 400) (Short $r)
$r = Invoke-Api POST "/api/accounts/$aliceAcc/withdrawals" -Token $aliceTok -IdemKey (Key "alice-bad-neg") -Body @{ amountPaise = -500 }
Check "25b negative amount -> 400" ($r.Status -eq 400) (Short $r)
$r = Invoke-Api POST "/api/accounts/$aliceAcc/withdrawals" -Token $aliceTok -IdemKey (Key "alice-bad-float") -RawBody '{"amountPaise":12.5}'
Check "25c decimal amount 12.5 -> 400" ($r.Status -eq 400) (Short $r)
$r = Invoke-Api POST "/api/accounts/$aliceAcc/withdrawals" -Token $aliceTok -Body @{ amountPaise = 100 }
Check "25d missing Idempotency-Key -> 400" ($r.Status -eq 400) (Short $r)

# ---------------------------------------------------------------- Part 4: Bob attacks Alice

$r = Invoke-Api POST "/api/accounts/$aliceAcc/withdrawals" -Token $bobTok -IdemKey (Key "bob-steal-1") -Body @{ amountPaise = 1000 }
Check "26 Bob withdraws from Alice's account -> 404" ($r.Status -eq 404) (Short $r)

$r = Invoke-Api POST "/api/transfers" -Token $bobTok -IdemKey (Key "bob-steal-2") -Body @{ fromAccountId = $aliceAcc; toAccountId = $bobAcc; amountPaise = 1000 }
Check "27 Bob transfers out of Alice's account -> 404" ($r.Status -eq 404) (Short $r)

$r = Invoke-Api GET "/api/accounts/$aliceAcc" -Token $bobTok
Check "28a Bob views Alice's account -> 404" ($r.Status -eq 404) (Short $r)

$r = Invoke-Api GET "/api/accounts" -Token $bobTok
$bobList = @($r.Json)
Check "28b Bob's list has only his account, no EXTERNAL" ($r.Status -eq 200 -and $bobList.Count -eq 1 -and $bobList[0].id -eq $bobAcc) (Short $r)

$r = Invoke-Api GET "/api/admin/users" -Token $bobTok
Check "29 holder calls admin endpoint -> 403" ($r.Status -eq 403) (Short $r)

# ---------------------------------------------------------------- Part 5: statements and auditor

$r = Invoke-Api GET "/api/accounts/$aliceAcc/statement" -Token $aliceTok
$bal = Get-Prop $r.Json @("balancePaise", "currentBalancePaise", "balance")
$lines = @(Get-Prop $r.Json @("lines", "entries"))
$anon = @($lines | Where-Object { $_.direction -eq "REFUSED" -and $_.initiatedByEmail -eq "another user" })
Check "30a Alice's statement balance = 50000" ($r.Status -eq 200 -and $bal -eq 50000) (Short $r)
Check "30b refused attempts by others show as 'another user' (>= 3)" ($anon.Count -ge 3) "found $($anon.Count)"
$leaked = @($lines | Where-Object { $_.initiatedByEmail -eq $bobEmail })
Check "30c Bob's email is hidden from Alice" ($leaked.Count -eq 0) "found $($leaked.Count)"

$audreyTok = Login $audreyEmail "audrey-pass-123"
$r = Invoke-Api GET "/api/accounts" -Token $audreyTok
$audExt = @($r.Json | Where-Object { $_.type -eq "EXTERNAL" })
Check "31a auditor sees all accounts incl. EXTERNAL" ($r.Status -eq 200 -and $audExt.Count -eq 1) (Short $r)

$r = Invoke-Api GET "/api/accounts/$aliceAcc/statement" -Token $audreyTok
$audLines = @(Get-Prop $r.Json @("lines", "entries"))
$realEmail = @($audLines | Where-Object { $_.initiatedByEmail -eq $bobEmail })
Check "31b auditor sees real emails on statement" ($r.Status -eq 200 -and $realEmail.Count -ge 1) (Short $r)

$r = Invoke-Api POST "/api/accounts/$aliceAcc/deposits" -Token $audreyTok -IdemKey (Key "audrey-dep") -Body @{ amountPaise = 100 }
Check "32 auditor deposit -> 403" ($r.Status -eq 403) (Short $r)

# ---------------------------------------------------------------- Part 6: disabling users

$r = Invoke-Api PATCH "/api/admin/users/$bobId/status" -Token $adminTok -Body @{ status = "DISABLED" }
Check "33a admin disables Bob" ($r.Status -eq 200) (Short $r)
$r = Invoke-Api GET "/api/auth/me" -Token $bobTok
Check "33b Bob's old token -> 401 immediately" ($r.Status -eq 401) (Short $r)
$r = Invoke-Api PATCH "/api/admin/users/$bobId/status" -Token $adminTok -Body @{ status = "ACTIVE" }
Check "33c admin re-enables Bob" ($r.Status -eq 200) (Short $r)

$r = Invoke-Api PATCH "/api/admin/users/$adminId/status" -Token $adminTok -Body @{ status = "DISABLED" }
Check "34 admin cannot disable self -> 400" ($r.Status -eq 400) (Short $r)

# ---------------------------------------------------------------- Part 7: balances and the books

$r = Invoke-Api GET "/api/accounts/$aliceAcc" -Token $adminTok
Check "35a Alice balance = 50000" ($r.Json.balancePaise -eq 50000) (Short $r)
$r = Invoke-Api GET "/api/accounts/$bobAcc" -Token $adminTok
Check "35b Bob balance = 30000" ($r.Json.balancePaise -eq 30000) (Short $r)

if (-not $SkipDbCheck) {
    try {
        $sum = (docker exec ledgerline-postgres psql -U postgres -d ledgerline -t -A -c "SELECT COALESCE(SUM(amount),0) FROM ledger_entries;" | Out-String).Trim()
        Check "36a DB: SUM(ledger_entries.amount) = 0" ($sum -eq "0") "got $sum"

        $off = (docker exec ledgerline-postgres psql -U postgres -d ledgerline -t -A -c "SELECT COUNT(*) FROM accounts a WHERE a.balance <> COALESCE((SELECT SUM(e.amount) FROM ledger_entries e WHERE e.account_id = a.id), 0);" | Out-String).Trim()
        Check "36b DB: every balance = sum of its ledger entries" ($off -eq "0") "accounts that don't match: $off"

        $replays = (docker exec ledgerline-postgres psql -U postgres -d ledgerline -t -A -c "SELECT replay_count FROM idempotency_keys WHERE idempotency_key = '$k15';" | Out-String).Trim()
        Check "36c DB: replayed key has replay_count = 1" ($replays -eq "1") "got $replays"
    } catch {
        Check "36 DB checks (docker exec)" $false "$_"
    }
}

# ---------------------------------------------------------------- summary

Write-Host ""
Write-Host "Passed: $script:passed   Failed: $script:failed" -ForegroundColor Cyan
if ($script:failed -gt 0) {
    Write-Host ""
    Write-Host "Failures:" -ForegroundColor Red
    $script:failures | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}
exit 0
