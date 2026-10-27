# 输出一行 python.exe 的完整路径（带引号）；找不到则不输出并以 1 退出。
# 由 _launch.bat 调用，用于 PATH 未刷新 / 提权到别的账户时兜底。
$ErrorActionPreference = 'SilentlyContinue'

$candidates = New-Object System.Collections.Generic.List[string]

foreach ($name in @('python.exe', 'py.exe')) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) { $candidates.Add($cmd.Source) }
}

foreach ($key in @(
    'HKLM:\SOFTWARE\Python\PythonCore\*\InstallPath',
    'HKCU:\SOFTWARE\Python\PythonCore\*\InstallPath',
    'HKLM:\SOFTWARE\WOW6432Node\Python\PythonCore\*\InstallPath'
)) {
    Get-Item $key -ErrorAction SilentlyContinue | ForEach-Object {
        $install = $_.GetValue('')
        if ($install) { $candidates.Add((Join-Path $install 'python.exe')) }
    }
}

$roots = @(
    "$env:LOCALAPPDATA\Programs\Python",
    "$env:ProgramFiles\Python",
    "${env:ProgramFiles(x86)}\Python",
    'C:\Python313', 'C:\Python312', 'C:\Python311', 'C:\Python310',
    'D:\Python313', 'D:\Python312', 'D:\Python311', 'D:\Python310',
    'D:\tools\python', 'D:\tools\Python312',
    "$env:USERPROFILE\scoop\apps\python\current",
    'C:\ProgramData\chocolatey\bin'
)

foreach ($dir in $roots) {
    if (-not (Test-Path $dir)) { continue }
    $candidates.Add((Join-Path $dir 'python.exe'))
    Get-ChildItem $dir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(?i)python' } |
        ForEach-Object { $candidates.Add((Join-Path $_.FullName 'python.exe')) }
}

foreach ($path in $candidates) {
    if (-not $path) { continue }
    if ($path -match 'WindowsApps') { continue }
    if (-not (Test-Path $path -PathType Leaf)) { continue }
    try {
        $size = (Get-Item $path).Length
        if ($size -lt 1024) { continue }
    } catch { continue }
    Write-Output ('"' + $path + '"')
    exit 0
}

exit 1
