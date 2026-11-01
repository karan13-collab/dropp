# 1. Build MSIX Package
dotnet build dropp-windows\dropp.Windows\dropp.Windows.csproj -c Debug -r win-x64 -p:Platform=x64 -p:GenerateAppxPackageOnBuild=true

# 2. Remove Existing Registration (if present)
Get-AppxPackage | Where-Object { $_.PackageFullName -match "0229faea" } | Remove-AppxPackage

# 3. Register Fresh AppxManifest
Add-AppxPackage -Register "dropp-windows\dropp.Windows\bin\x64\Debug\net8.0-windows10.0.19041.0\win-x64\AppxManifest.xml" -ForceUpdateFromAnyVersion

# 4. Launch Packaged App
Start-Process "shell:AppsFolder\0229faea-d7f2-4edd-9989-1fa0a2abda7b_4g7djxdmvnq5a!App"
