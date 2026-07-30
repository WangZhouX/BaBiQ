param(
    [Parameter(Mandatory = $true)]
    [string]$ReadyReport
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-JsonResponse {
    param(
        [Parameter(Mandatory = $true)] [Net.HttpListenerContext]$Context,
        [Parameter(Mandatory = $true)] [string]$Body,
        [int]$StatusCode = 200
    )
    $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
    $Context.Response.StatusCode = $StatusCode
    $Context.Response.ContentType = 'application/json; charset=utf-8'
    $Context.Response.ContentLength64 = $bytes.Length
    $Context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Context.Response.Close()
}

function Success-Json {
    param([Parameter(Mandatory = $true)] [object]$Data)
    return (@{ code = 0; msg = ''; data = $Data } | ConvertTo-Json -Compress -Depth 12)
}

function Get-Md5Hex {
    param([Parameter(Mandatory = $true)] [string]$Value)
    $md5 = [Security.Cryptography.MD5]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return -join ($md5.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') })
    } finally {
        $md5.Dispose()
    }
}

function Get-DoubleMd5 {
    param([Parameter(Mandatory = $true)] [string]$Value)
    return Get-Md5Hex -Value (Get-Md5Hex -Value ($Value + 'huitaisystem'))
}

$account = [Environment]::GetEnvironmentVariable('HUITAI_DESKTOP_AUTH_SMOKE_ACCOUNT', 'Process')
$password = [Environment]::GetEnvironmentVariable('HUITAI_DESKTOP_AUTH_SMOKE_PASSWORD', 'Process')
$accessToken = [Environment]::GetEnvironmentVariable('HUITAI_DESKTOP_AUTH_SMOKE_ACCESS', 'Process')
$refreshToken = [Environment]::GetEnvironmentVariable('HUITAI_DESKTOP_AUTH_SMOKE_REFRESH', 'Process')
if ([string]::IsNullOrWhiteSpace($account) -or
    [string]::IsNullOrWhiteSpace($password) -or
    [string]::IsNullOrWhiteSpace($accessToken) -or
    [string]::IsNullOrWhiteSpace($refreshToken)) {
    throw 'Authenticated packaged-smoke fake OA configuration is incomplete.'
}

$portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$portProbe.Start()
$port = ([Net.IPEndPoint]$portProbe.LocalEndpoint).Port
$portProbe.Stop()

$listener = [Net.HttpListener]::new()
$baseUrl = "http://127.0.0.1:$port"
$listener.Prefixes.Add("$baseUrl/")
$listener.Start()
try {
    $readyDirectory = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($ReadyReport))
    if (-not (Test-Path -LiteralPath $readyDirectory -PathType Container)) {
        throw 'Fake OA ready-report directory is unavailable.'
    }
    @{ baseUrl = $baseUrl } | ConvertTo-Json -Compress |
        Set-Content -LiteralPath $ReadyReport -Encoding UTF8

    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            $path = $context.Request.Url.AbsolutePath
            $response = switch ($path) {
                '/law-api/system/auth/get-users-by-mobile' {
                    Success-Json @(
                        @{
                            userId = 1001
                            tenantId = 2002
                            platformId = 2
                            tenantName = 'Packaged Smoke Tenant'
                            tenantEnterStatus = 0
                        }
                    )
                }
                '/law-api/system/auth/login' {
                    $reader = [IO.StreamReader]::new($context.Request.InputStream, $context.Request.ContentEncoding)
                    try { $loginBody = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
                    if ($loginBody.mobileOrEmail -ne $account -or
                        $loginBody.password -ne (Get-DoubleMd5 -Value $password)) {
                        throw 'Unexpected fake OA login credentials.'
                    }
                    Success-Json @{
                        accessToken = $accessToken
                        refreshToken = $refreshToken
                        userId = 1001
                        expiresTime = 9999999999
                    }
                }
                '/law-api/system/auth/refresh-token' {
                    Success-Json @{
                        accessToken = $accessToken
                        refreshToken = $refreshToken
                        userId = 1001
                        expiresTime = 9999999999
                    }
                }
                '/law-api/system/auth/get-permission-info' {
                    Success-Json @{
                        permissions = @('law:case:query')
                        roles = @('lawyer')
                        user = @{ id = 1001; nickname = 'Packaged Smoke User' }
                        menus = @()
                    }
                }
                '/law-api/system/auth/logout' { Success-Json $true }
                '/law-api/system/notice-push/page' {
                    Success-Json @{ total = 1; pageNo = 1; pageSize = 10; list = @(@{ id = 'notice-1'; title = 'Smoke notice' }) }
                }
                '/law-api/lawyer/home-config/list-shortcut' {
                    Success-Json @(@{ id = 'shortcut-1'; name = 'Cases'; url = '/case' })
                }
                '/law-api/lawyer/home-config/summary' {
                    Success-Json @(@{ id = 'summary-1'; configName = 'Pending'; total = 1; stat = @{ handling = 1 } })
                }
                '/law-api/system/user/home-info' {
                    Success-Json @{ userId = 1001; tenantId = 2002; nickname = 'Packaged Smoke User'; avatar = 'avatar-handle' }
                }
                '/law-api/system/team/list' {
                    Success-Json @(@{ id = 'team-1'; name = 'Packaged Smoke Team'; userId = 1001 })
                }
                '/law-api/lawyer/law-schedule/list-count' {
                    Success-Json @{ schDate = '2026-07-29'; schCount = 1 }
                }
                '/law-api/lawyer/law-schedule/list-day' {
                    Success-Json @{ list = @(@{ id = 'schedule-1'; title = 'Smoke schedule'; schTime = '10:00' }) }
                }
                default { throw 'Unexpected fake OA route.' }
            }
            Write-JsonResponse -Context $context -Body $response
        } catch {
            $failure = '{"code":500,"msg":"fake OA contract failure","data":null}'
            Write-JsonResponse -Context $context -Body $failure -StatusCode 500
        }
    }
} finally {
    $listener.Stop()
    $listener.Close()
}
