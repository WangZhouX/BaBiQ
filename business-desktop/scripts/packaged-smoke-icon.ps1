Add-Type -AssemblyName System.Drawing

function Get-NormalizedBitmapPixelDigest {
    param(
        [Parameter(Mandatory = $true)]
        [Drawing.Bitmap]$Bitmap
    )
    $sha256 = $null
    try {
        $bytes = New-Object byte[] ($Bitmap.Width * $Bitmap.Height * 4)
        $offset = 0
        for ($y = 0; $y -lt $Bitmap.Height; $y++) {
            for ($x = 0; $x -lt $Bitmap.Width; $x++) {
                $argb = $Bitmap.GetPixel($x, $y).ToArgb()
                $bytes[$offset] = [byte](($argb -shr 24) -band 0xFF)
                $bytes[$offset + 1] = [byte](($argb -shr 16) -band 0xFF)
                $bytes[$offset + 2] = [byte](($argb -shr 8) -band 0xFF)
                $bytes[$offset + 3] = [byte]($argb -band 0xFF)
                $offset += 4
            }
        }
        $sha256 = [Security.Cryptography.SHA256]::Create()
        return ([BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '')
    } finally {
        if ($null -ne $sha256) { $sha256.Dispose() }
    }
}

function Get-BrandIconFrameBitmap {
    param(
        [Parameter(Mandatory = $true)] [string]$BrandIconPath,
        [Parameter(Mandatory = $true)] [int]$Width,
        [Parameter(Mandatory = $true)] [int]$Height
    )
    $bytes = [IO.File]::ReadAllBytes([IO.Path]::GetFullPath($BrandIconPath))
    if ($bytes.Length -lt 22 -or [BitConverter]::ToUInt16($bytes, 0) -ne 0 -or
        [BitConverter]::ToUInt16($bytes, 2) -ne 1) {
        return $null
    }
    $count = [BitConverter]::ToUInt16($bytes, 4)
    for ($index = 0; $index -lt $count; $index++) {
        $entryOffset = 6 + ($index * 16)
        if ($entryOffset + 16 -gt $bytes.Length) { return $null }
        $entryWidth = if ($bytes[$entryOffset] -eq 0) { 256 } else { [int]$bytes[$entryOffset] }
        $entryHeight = if ($bytes[$entryOffset + 1] -eq 0) { 256 } else { [int]$bytes[$entryOffset + 1] }
        if ($entryWidth -ne $Width -or $entryHeight -ne $Height) { continue }
        $payloadLength = [BitConverter]::ToUInt32($bytes, $entryOffset + 8)
        $payloadOffset = [BitConverter]::ToUInt32($bytes, $entryOffset + 12)
        if ($payloadLength -eq 0 -or $payloadOffset + $payloadLength -gt $bytes.Length) { return $null }
        $payload = New-Object byte[] ([int]$payloadLength)
        [Array]::Copy($bytes, [int]$payloadOffset, $payload, 0, [int]$payloadLength)
        $stream = $null
        $decoded = $null
        try {
            $stream = [IO.MemoryStream]::new($payload, $false)
            $decoded = [Drawing.Bitmap]::new($stream)
            return [Drawing.Bitmap]$decoded.Clone()
        } finally {
            if ($null -ne $decoded) { $decoded.Dispose() }
            if ($null -ne $stream) { $stream.Dispose() }
        }
    }
    return $null
}

function Test-LauncherBrandIcon {
    param(
        [Parameter(Mandatory = $true)] [string]$LauncherPath,
        [Parameter(Mandatory = $true)] [string]$BrandIconPath
    )
    if (-not (Test-Path -LiteralPath $LauncherPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $BrandIconPath -PathType Leaf)) {
        return $false
    }
    $launcherIcon = $null
    $launcherBitmap = $null
    $brandBitmap = $null
    try {
        $launcherIcon = [Drawing.Icon]::ExtractAssociatedIcon([IO.Path]::GetFullPath($LauncherPath))
        if ($null -eq $launcherIcon -or $launcherIcon.Width -lt 16 -or $launcherIcon.Height -lt 16) {
            return $false
        }
        $launcherBitmap = $launcherIcon.ToBitmap()
        $brandBitmap = Get-BrandIconFrameBitmap -BrandIconPath $BrandIconPath -Width $launcherIcon.Width -Height $launcherIcon.Height
        if ($null -eq $brandBitmap) { return $false }
        $launcherDigest = Get-NormalizedBitmapPixelDigest -Bitmap $launcherBitmap
        $brandDigest = Get-NormalizedBitmapPixelDigest -Bitmap $brandBitmap
        return $launcherDigest -eq $brandDigest
    } catch {
        return $false
    } finally {
        if ($null -ne $brandBitmap) { $brandBitmap.Dispose() }
        if ($null -ne $launcherBitmap) { $launcherBitmap.Dispose() }
        if ($null -ne $launcherIcon) { $launcherIcon.Dispose() }
    }
}
