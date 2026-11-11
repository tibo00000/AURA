param([switch]$CheckOnly, [switch]$SelfTest)
$ErrorActionPreference = 'Stop'
# Local preparation only. No SQL/HTTP, export or existing credential modification.
# Run from an interactive Windows PowerShell STA console. Never enable tracing/transcripts.
$auraReference = 'neosgkuccazvgkzwzjzh'
$auraRoot = 'C:\Users\thiba\AURA-private-recovery\SEC-002.5'
$auraRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$auraTools = Join-Path $auraRepo 'scratch\sec0025\tools\age\age'
$auraEntropy = [Text.Encoding]::UTF8.GetBytes('AURA-SEC0025-M2S-v1')

function Protect-AuraBytes([byte[]]$Bytes) {
    return ,([Security.Cryptography.ProtectedData]::Protect($Bytes, $auraEntropy,
        [Security.Cryptography.DataProtectionScope]::CurrentUser))
}
function Read-AuraMasked([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
}
function Invoke-AuraAge([string]$Tool, [string]$Arguments, [string]$InputText) {
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = Join-Path $auraTools $Tool
    $start.Arguments = $Arguments
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.RedirectStandardInput = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'age_start_failed' }
        if ($InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        $outputText = $process.StandardOutput.ReadToEnd()
        $null = $process.StandardError.ReadToEnd() # Never print private process errors.
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'age_failed' }
        return $outputText
    } finally { $process.Dispose() }
}
function Assert-AuraPath([string]$Path) {
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'reparse_point_refused' }
        if ($item -is [IO.FileInfo]) { $item = $item.Directory } else { $item = $item.Parent }
    }
}
function Set-AuraPrivateDirectory([string]$Path) {
    $null = [IO.Directory]::CreateDirectory($Path)
    Assert-AuraPath $Path
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $operator = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($operator)
    foreach ($sid in @($operator, (New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))) {
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    $actual = Get-Acl -LiteralPath $Path
    $allowed = @($operator.Value, 'S-1-5-18')
    if (-not $actual.AreAccessRulesProtected -or @($actual.GetAccessRules($true,$true,
        [Security.Principal.SecurityIdentifier]) | Where-Object { $_.IdentityReference.Value -notin $allowed }).Count) {
        throw 'private_acl_not_established'
    }
}
function Write-AuraNewBytes([string]$Path, [byte[]]$Bytes) {
    $stream = New-Object IO.FileStream($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($Bytes,0,$Bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
}
function New-AuraPrivateClipboardData([string]$Text) {
    # Official Win32 clipboard formats: serialized DWORD0 disables history/cloud upload.
    $data = New-Object Windows.Forms.DataObject
    $data.SetData([Windows.Forms.DataFormats]::UnicodeText,$Text)
    foreach ($format in @('CanIncludeInClipboardHistory','CanUploadToCloudClipboard')) {
        $data.SetData($format,$false,(New-Object IO.MemoryStream(,[byte[]](0,0,0,0))))
    }
    return $data
}

try {
    Add-Type -AssemblyName System.Security
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archivePath = Join-Path $auraRepo 'scratch\sec0025\tools\age-v1.3.2-windows-arm64.zip'
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne
        'fae351336c8d5f30f93cc3ccdec16d1ed7851d68cdd6656d41955cc31a447c9d') { throw 'untrusted_age_archive' }
    $zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($tool in @('age.exe','age-keygen.exe')) {
            $entries = @($zip.Entries | Where-Object { $_.FullName -eq ('age/'+$tool) })
            if ($entries.Count -ne 1) { throw 'unexpected_age_archive' }
            $entryStream = $entries[0].Open()
            $hash = [Security.Cryptography.SHA256]::Create()
            try { $expected = [BitConverter]::ToString($hash.ComputeHash($entryStream)).Replace('-','') }
            finally { $hash.Dispose(); $entryStream.Dispose() }
            if ((Get-FileHash -LiteralPath (Join-Path $auraTools $tool) -Algorithm SHA256).Hash -ne $expected) {
                throw 'age_binary_mismatch'
            }
        }
    } finally { $zip.Dispose() }
    $probe = [Text.Encoding]::UTF8.GetBytes('AURA synthetic DPAPI self-check')
    $sealed = Protect-AuraBytes $probe
    $opened = [Security.Cryptography.ProtectedData]::Unprotect($sealed,$auraEntropy,
        [Security.Cryptography.DataProtectionScope]::CurrentUser)
    if ([Convert]::ToBase64String($opened) -ne [Convert]::ToBase64String($probe)) { throw 'dpapi_roundtrip_failed' }
    if ($SelfTest) {
        Add-Type -AssemblyName System.Windows.Forms
        $clipboardTest = New-AuraPrivateClipboardData 'AURA synthetic clipboard format test'
        foreach ($format in @('CanIncludeInClipboardHistory','CanUploadToCloudClipboard')) {
            $buffer = $clipboardTest.GetData($format).ToArray()
            if ($buffer.Length -ne 4 -or [BitConverter]::ToUInt32($buffer,0) -ne 0) { throw 'clipboard_privacy_flags_failed' }
        }
        $keyTest = Invoke-AuraAge 'age-keygen.exe' '' ''
        $identityTest = [regex]::Match($keyTest,'(?m)^AGE-SECRET-KEY-1[A-Z0-9]+\r?$').Value.Trim()
        if (-not $identityTest -or (Invoke-AuraAge 'age-keygen.exe' '-y' ($identityTest+"`n")).Trim() -notmatch '^age1[a-z0-9]+$') {
            throw 'ephemeral_keygen_test_failed'
        }
        $keyTest=$null; $identityTest=$null
        $knownCa = Get-ChildItem Cert:\LocalMachine\Root | Where-Object { $_.NotAfter -gt (Get-Date) } | Select-Object -First 1
        $publicBytes = $knownCa.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert)
        $certTest = New-Object Security.Cryptography.X509Certificates.X509Certificate2(,$publicBytes)
        try {
            if (-not ($certTest.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.19' }).CertificateAuthority) {
                throw 'CA_parser_test_failed'
            }
        } finally { $certTest.Dispose() }
        $testParent = Join-Path $auraRepo 'scratch\sec0025'
        $testPath = [IO.Path]::GetFullPath((Join-Path $testParent ('access-selftest-'+[Guid]::NewGuid().ToString('N'))))
        if (-not $testPath.StartsWith(([IO.Path]::GetFullPath($testParent)+[IO.Path]::DirectorySeparatorChar),[StringComparison]::OrdinalIgnoreCase)) {
            throw 'invalid_selftest_path'
        }
        try {
            Set-AuraPrivateDirectory $testPath
            $filePath = Join-Path $testPath 'synthetic.dpapi'
            Write-AuraNewBytes $filePath $sealed
            $stored = [IO.File]::ReadAllBytes($filePath)
            $restored = [Security.Cryptography.ProtectedData]::Unprotect($stored,$auraEntropy,
                [Security.Cryptography.DataProtectionScope]::CurrentUser)
            if ([Convert]::ToBase64String($restored) -ne [Convert]::ToBase64String($probe)) { throw 'stored_dpapi_check_failed' }
            $overwriteRefused = $false
            try { Write-AuraNewBytes $filePath ([byte[]](1,2,3)) } catch { $overwriteRefused = $true }
            if (-not $overwriteRefused -or [Convert]::ToBase64String([IO.File]::ReadAllBytes($filePath)) -ne [Convert]::ToBase64String($stored)) {
                throw 'existing_file_changed'
            }
        } finally {
            if (Test-Path -LiteralPath $testPath) {
                Assert-AuraPath $testPath
                Remove-Item -LiteralPath $testPath -Recurse -Force
            }
        }
        Write-Output '{"mode":"synthetic_self_test","age_binaries_verified":true,"ephemeral_keygen_test":true,"CA_parser":true,"clipboard_history_and_cloud_disabled_formats":true,"dpapi_file_roundtrip":true,"operator_system_acl_verified":true,"overwrite_refused":true,"temporary_synthetic_files_removed":true,"network_accessed":false,"real_credentials_read":false}'
        exit 0
    }
    if ($CheckOnly) {
        Write-Output '{"mode":"check_only","age_binaries_verified":true,"dpapi_roundtrip":true,"network_accessed":false,"credentials_read":false,"files_created":false}'
        exit 0
    }
    if ([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') { throw 'STA_console_required' }
    Write-Host "M2-S local uniquement - projet production $auraReference. Aucun export ni acces SQL."
    Write-Host 'Ne collez jamais de mot de passe, token ou cle dans la conversation.'
    $sqlHost = (Read-Host 'Host SQL depuis Connect > Session pooler (host seul, port5432; vide = direct)').Trim().ToLowerInvariant()
    if (-not $sqlHost) { $sqlHost = "db.$auraReference.supabase.co" }
    if ($sqlHost -eq "db.$auraReference.supabase.co") { $sqlUser = 'postgres'; $mode = 'direct' }
    elseif ($sqlHost -match '^aws-[0-9]+-[a-z0-9-]+\.pooler\.supabase\.com$') {
        $sqlUser = "postgres.$auraReference"; $mode = 'session_pooler'
    } else { throw 'unapproved_SQL_target' }
    $caPath = (Read-Host 'Chemin du certificat CA telecharge depuis Database Settings du projet production').Trim().Trim('"')
    Assert-AuraPath $caPath
    $caBytes = [IO.File]::ReadAllBytes($caPath)
    if ($caBytes.Length -gt 131072 -or $caBytes.Length -lt 100) { throw 'invalid_certificate_size' }
    $caText = [Text.Encoding]::ASCII.GetString($caBytes)
    if ($caText -match 'PRIVATE KEY') { throw 'private_key_refused_as_CA' }
    $certBytes = $caBytes
    if ($caText -match '-----BEGIN CERTIFICATE-----([\s\S]*?)-----END CERTIFICATE-----') {
        $certBytes = [Convert]::FromBase64String(($Matches[1] -replace '\s',''))
    }
    $certificate = New-Object Security.Cryptography.X509Certificates.X509Certificate2(,$certBytes)
    try {
        $basic = $certificate.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.19' }
        if (-not $basic -or -not $basic.CertificateAuthority -or $certificate.NotAfter -lt (Get-Date)) {
            throw 'invalid_or_expired_CA'
        }
    } finally { $certificate.Dispose() }
    $password = Read-AuraMasked 'Mot de passe PostgreSQL EXISTANT du projet production (masque)'
    $passwordAgain = Read-AuraMasked 'Confirmer le meme mot de passe (masque)'
    if ([string]::IsNullOrWhiteSpace($password) -or $password -cne $passwordAgain) { throw 'password_confirmation_failed' }
    $passwordAgain = $null

    # New dedicated identity only. Captured in memory; never stdout, disk or logs in clear.
    $keyOutput = Invoke-AuraAge 'age-keygen.exe' '' ''
    $identity = [regex]::Match($keyOutput,'(?m)^AGE-SECRET-KEY-1[A-Z0-9]+\r?$').Value.Trim()
    if (-not $identity) { throw 'age_identity_generation_failed' }
    $recipient = (Invoke-AuraAge 'age-keygen.exe' '-y' ($identity+"`n")).Trim()
    if ($recipient -notmatch '^age1[a-z0-9]+$') { throw 'invalid_age_recipient' }
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    $form = New-Object Windows.Forms.Form
    $form.Text = 'AURA M2-S - secours de la cle age - LOCAL PRIVE'
    $form.Width = 730; $form.Height = 340; $form.StartPosition = 'CenterScreen'
    $label = New-Object Windows.Forms.Label
    $label.SetBounds(15,15,680,60)
    $label.Text = 'Enregistrez la nouvelle cle dans une note securisee de votre gestionnaire de mots de passe. Puis relisez-la depuis ce gestionnaire dans le champ masque. Ne partagez pas cette fenetre ni son contenu.'
    $copy = New-Object Windows.Forms.Button; $copy.SetBounds(15,80,680,32)
    $copy.Text = 'Copier la cle de secours (historique et synchronisation du presse-papiers desactives pour cette copie)'
    $copy.Add_Click({
        try {
            $data = New-AuraPrivateClipboardData $identity
            [Windows.Forms.Clipboard]::SetDataObject($data,$true)
            $copy.Text = 'Cle copiee : enregistrez-la dans le gestionnaire, puis relisez-la ci-dessous.'
        } catch { [Windows.Forms.MessageBox]::Show('Copie impossible. Aucun secret affiche.'); }
    })
    $again = New-Object Windows.Forms.TextBox; $again.SetBounds(15,125,680,25)
    $again.UseSystemPasswordChar = $true
    $confirmed = New-Object Windows.Forms.CheckBox; $confirmed.SetBounds(15,165,680,35)
    $confirmed.Text = 'La cle est enregistree dans le gestionnaire et ce coffre reste accessible depuis un autre appareil.'
    $save = New-Object Windows.Forms.Button; $save.SetBounds(15,215,330,32); $save.Text = 'Verifier et enregistrer localement'
    $cancel = New-Object Windows.Forms.Button; $cancel.SetBounds(365,215,330,32); $cancel.Text = 'Annuler sans enregistrer'
    $cancel.DialogResult = [Windows.Forms.DialogResult]::Cancel
    $save.Add_Click({
        if (-not $confirmed.Checked -or $again.Text.Trim() -cne $identity) {
            $null = [Windows.Forms.MessageBox]::Show('Confirmez le secours independant et relisez la meme cle depuis le gestionnaire. Aucun secret affiche.')
        } else { $form.DialogResult = [Windows.Forms.DialogResult]::OK; $form.Close() }
    })
    $form.Controls.AddRange(@($label,$copy,$again,$confirmed,$save,$cancel))
    try { $answer = $form.ShowDialog() }
    finally {
        if ([Windows.Forms.Clipboard]::ContainsText() -and [Windows.Forms.Clipboard]::GetText() -ceq $identity) {
            [Windows.Forms.Clipboard]::Clear()
        }
        $again.Clear(); $form.Dispose()
    }
    if ($answer -ne [Windows.Forms.DialogResult]::OK) { throw 'cancelled_before_save' }
    $runId = 'm2s-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
    $runPath = Join-Path $auraRoot $runId
    if (Test-Path -LiteralPath $auraRoot) { Assert-AuraPath $auraRoot }
    Set-AuraPrivateDirectory $auraRoot
    Set-AuraPrivateDirectory $runPath
    $connection = [ordered]@{ project_ref=$auraReference; host=$sqlHost; port=5432; dbname='postgres';
        user=$sqlUser; password=$password; sslmode='verify-full'; sslrootcert=(Join-Path $runPath 'database-ca.crt');
        mode=$mode; connect_timeout=10; application_name='aura-sec0025-m2s-readonly' }
    $plain = [Text.Encoding]::UTF8.GetBytes(($connection | ConvertTo-Json -Compress))
    try { Write-AuraNewBytes (Join-Path $runPath 'connection.dpapi') (Protect-AuraBytes $plain) }
    finally { [Array]::Clear($plain,0,$plain.Length) }
    $plain = [Text.Encoding]::UTF8.GetBytes($identity+"`n")
    try { Write-AuraNewBytes (Join-Path $runPath 'age-identity.dpapi') (Protect-AuraBytes $plain) }
    finally { [Array]::Clear($plain,0,$plain.Length) }
    Write-AuraNewBytes (Join-Path $runPath 'database-ca.crt') $caBytes
    $manifest = [ordered]@{ format='aura-sec0025-access-v1'; run_id=$runId; project_ref=$auraReference;
        prepared_at=[DateTime]::UtcNow.ToString('o'); age_recipient=$recipient; key_backup_method='password_manager';
        key_backup_confirmed_by_operator=$true; key_backup_reentry_matches=$true; key_backup_independent_confirmed=$true;
        private_export_started=$false; retention_days_after_export=7; dpapi_scope='CurrentUser'; network_accessed=$false }
    Write-AuraNewBytes (Join-Path $runPath 'access-manifest.json') ([Text.Encoding]::UTF8.GetBytes(($manifest | ConvertTo-Json)))
    $password=$null; $connection=$null; $identity=$null; $keyOutput=$null
    Write-Output ('Preparation locale terminee : '+$runPath)
    Write-Output 'Aucun acces SQL, export ou changement distant. Prevenez Codex pour verifier TLS/droits avant export.'
} catch {
    # Never print exception/input/DSN values; failure may contain a secret.
    Write-Error 'Preparation interrompue. Aucun secret affiche. Aucun acces distant ou export effectue. Ne modifiez pas les secrets Supabase.' -ErrorAction Continue
    exit 1
}
