param(
    [string]$RunDirectory,
    [switch]$CheckOnly,
    [switch]$ReplaceDatabasePassword
)
$ErrorActionPreference = 'Stop'

# LOCAL ONLY. No HTTP/SQL, no account creation, no existing production configuration.
# Run interactively in PowerShell; secrets are never arguments, output or command history.
try {
    if ($CheckOnly -and $ReplaceDatabasePassword) { throw 'Conflicting options' }
    $reference = 'nknbuhccnvvhrghvhmll'
    if (-not $RunDirectory) {
        $relative = "AURA-sec0023-$reference\run"
        $candidates = @(
            (Join-Path $env:LOCALAPPDATA $relative),
            (Join-Path $env:LOCALAPPDATA "Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\$relative")
        )
        $RunDirectory = $candidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'manifest.json') } | Select-Object -First 1
    }
    if (-not $RunDirectory) { throw 'Missing test directory' }
    $RunDirectory = (Resolve-Path -LiteralPath $RunDirectory).Path
    $parent = Split-Path -Parent $RunDirectory
    $sid = ([System.Security.Principal.WindowsIdentity]::GetCurrent()).User.Value
    $allowed = @($sid, 'S-1-5-18')
    $acl = Get-Acl -LiteralPath $parent
    if (-not $acl.AreAccessRulesProtected) { throw 'Unprotected parent' }
    $rules = $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])
    if (@($rules | Where-Object { $_.IdentityReference.Value -notin $allowed }).Count) { throw 'Unexpected ACL' }
    $manifestPath = Join-Path $RunDirectory 'manifest.json'
    $envPath = Join-Path $RunDirectory 'backend.env'
    $sqlPath = Join-Path $RunDirectory 'sql-control.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $sql = Get-Content -LiteralPath $sqlPath -Raw | ConvertFrom-Json
    if ($manifest.isolated_project_ref -ne $reference -or
        $manifest.supabase_url -ne "https://$reference.supabase.co" -or
        $sql.host -ne "db.$reference.supabase.co" -or $sql.user -ne 'postgres') {
        throw 'Wrong test target'
    }
    $environment = [IO.File]::ReadAllText($envPath)
    $match = [regex]::Match($environment, '(?m)^SUPABASE_SERVICE_ROLE_KEY=([^\r\n]*)')
    if (-not $match.Success) { throw 'Missing key field' }
    $keyPresent = -not [string]::IsNullOrWhiteSpace($match.Groups[1].Value)
    $passwordPresent = -not [string]::IsNullOrWhiteSpace($sql.password)
    $certificatePresent = Test-Path -LiteralPath $sql.sslrootcert -PathType Leaf
    if ($CheckOnly) {
        [ordered]@{ target = $reference; server_key_present = $keyPresent; database_password_present = $passwordPresent; database_certificate_present = $certificatePresent; remote_accessed = $false } | ConvertTo-Json -Compress
        exit 0
    }
    function Read-MaskedValue([string]$Prompt) {
        $secure = Read-Host $Prompt -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
            $secure.Dispose()
        }
    }
    if (-not $keyPresent -and -not $ReplaceDatabasePassword) {
        $key = Read-MaskedValue 'Cle serveur DU PROJET TEST (saisie masquee)'
        if ($key -notmatch '^(sb_secret_[A-Za-z0-9_-]+|[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$') {
            throw 'Invalid key format'
        }
    }
    if (-not $passwordPresent -or $ReplaceDatabasePassword) {
        $password = Read-MaskedValue 'Mot de passe PostgreSQL DU PROJET TEST (saisie masquee)'
        if ([string]::IsNullOrWhiteSpace($password)) { throw 'Empty password' }
        $sql.password = $password
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    if (-not $keyPresent -and -not $ReplaceDatabasePassword) {
        $environment = $environment.Replace($match.Value, ('SUPABASE_SERVICE_ROLE_KEY=' + $key))
        [IO.File]::WriteAllText($envPath, $environment, $utf8)
    }
    if (-not $passwordPresent -or $ReplaceDatabasePassword) { [IO.File]::WriteAllText($sqlPath, ($sql | ConvertTo-Json -Depth 10), $utf8) }
    $key = $null; $password = $null; $environment = $null; $sql = $null
    Write-Output 'Acces de test enregistres localement. Aucun acces distant effectue.'
    if (-not $certificatePresent) { Write-Output 'Reste a enregistrer le certificat CA du projet test dans run\database-ca.crt.' }
}
catch {
    # Never echo exception values: a parser/validation error could otherwise include input.
    Write-Error 'Saisie interrompue. Verifier le dossier de test, ses ACL et les champs. Aucun secret affiche.' -ErrorAction Continue
    exit 1
}
