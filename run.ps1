[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "    AADL 需求分析工具" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$apiUrl = "http://localhost:8080/api/requirement"

Write-Host "正在检测服务状态..." -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$apiUrl/health" -Method Get -TimeoutSec 5
    Write-Host "服务运行正常" -ForegroundColor Green
    Write-Host "  Agent: $($response.agent)" -ForegroundColor Gray
    Write-Host ""
} catch {
    Write-Host "服务未启动或无法访问" -ForegroundColor Red
    Write-Host "  请先运行: mvn spring-boot:run" -ForegroundColor Gray
    exit 1
}

Write-Host "请选择操作:" -ForegroundColor Yellow
Write-Host "  1. 需求分析 (Agent1: 需求条目化)" -ForegroundColor Gray
Write-Host "  2. 架构生成 (Agent2: AADL架构生成)" -ForegroundColor Gray
Write-Host "  3. 模块分析 (Agent3: 功能模块分析)" -ForegroundColor Gray
Write-Host "  4. AADL生成 (Agent4: AADL模型生成)" -ForegroundColor Gray
Write-Host "  5. AADL修复 (修复语法错误)" -ForegroundColor Gray
Write-Host "  6. 知识库管理" -ForegroundColor Gray

$actionIdx = 0
do {
    $actionChoice = Read-Host "请输入操作序号 (1-6)"
} while (-not [int]::TryParse($actionChoice, [ref]$actionIdx) -or $actionIdx -lt 1 -or $actionIdx -gt 6)

if ($actionIdx -eq 1) {
    $inputDir = ".\input"
    Write-Host ""
    Write-Host "获取可用文件列表..." -ForegroundColor Yellow
    try {
        $files = Get-ChildItem -Path $inputDir -File | Where-Object {
            $_.Extension -eq ".doc" -or $_.Extension -eq ".docx" -or $_.Extension -eq ".txt"
        } | Select-Object Name, FullName
        
        $fileCount = @($files).Count
        if ($fileCount -eq 0) {
            Write-Host "input目录下没有找到可处理的文件" -ForegroundColor Red
            exit 1
        }
        
        Write-Host "找到 $fileCount 个文件:" -ForegroundColor Green
        for ($i = 0; $i -lt $fileCount; $i++) {
            Write-Host "  $($i + 1). $($files[$i].Name)" -ForegroundColor Gray
        }
        Write-Host ""
    } catch {
        Write-Host "获取文件列表失败: $_" -ForegroundColor Red
        exit 1
    }

    $idx = 0
    do {
        $choice = Read-Host "请输入要处理的文件序号 (1-$fileCount)"
    } while (-not [int]::TryParse($choice, [ref]$idx) -or $idx -lt 1 -or $idx -gt $fileCount)

    $selectedFile = $files[$idx - 1].Name

    Write-Host ""
    Write-Host "选择的文件: $selectedFile" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "选择模型类型:" -ForegroundColor Yellow
    Write-Host "  1. DeepSeek (在线API)" -ForegroundColor Gray
    Write-Host "  2. Ollama (本地模型)" -ForegroundColor Gray

    $modelIdx = 0
    do {
        $modelChoice = Read-Host "请输入模型序号 (1-2)"
    } while (-not [int]::TryParse($modelChoice, [ref]$modelIdx) -or $modelIdx -lt 1 -or $modelIdx -gt 2)

    $model = "DEEPSEEK"
    if ($modelIdx -eq 2) {
        $model = "OLLAMA"
    }

    Write-Host ""
    Write-Host "开始需求分析..." -ForegroundColor Yellow
    Write-Host "文件: $selectedFile" -ForegroundColor Gray
    Write-Host "模型: $model" -ForegroundColor Gray
    Write-Host ""

    $bodyObj = @{
        fileName = $selectedFile
        model = $model
    }
    $body = $bodyObj | ConvertTo-Json -Depth 10

    try {
        $webClient = New-Object System.Net.WebClient
        $webClient.Encoding = [System.Text.Encoding]::UTF8
        $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
        $resultJson = $webClient.UploadString("$apiUrl/process-file", "POST", $body)
        $result = $resultJson | ConvertFrom-Json
        
        Write-Host ""
        if ($result.success) {
            Write-Host "需求分析成功!" -ForegroundColor Green
            Write-Host "  输出文件: output/requirements/$($result.outputFile)" -ForegroundColor Cyan
            Write-Host "  耗时: $($result.executionTime)ms" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        } else {
            Write-Host "需求分析失败!" -ForegroundColor Red
            Write-Host "  错误: $($result.error)" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        }
    } catch {
        Write-Host ""
        Write-Host "请求失败: $_" -ForegroundColor Red
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Cyan
    }
} elseif ($actionIdx -eq 2) {
    $requirementsDir = ".\output\requirements"
    Write-Host ""
    Write-Host "获取可用的需求分析结果..." -ForegroundColor Yellow
    try {
        $files = Get-ChildItem -Path $requirementsDir -File | Where-Object {
            $_.Extension -eq ".json"
        } | Select-Object Name, FullName
        
        $fileCount = @($files).Count
        if ($fileCount -eq 0) {
            Write-Host "output/requirements目录下没有找到需求分析结果文件" -ForegroundColor Red
            Write-Host "请先运行需求分析(操作1)" -ForegroundColor Gray
            exit 1
        }
        
        Write-Host "找到 $fileCount 个需求文件:" -ForegroundColor Green
        for ($i = 0; $i -lt $fileCount; $i++) {
            Write-Host "  $($i + 1). $($files[$i].Name)" -ForegroundColor Gray
        }
        Write-Host ""
    } catch {
        Write-Host "获取文件列表失败: $_" -ForegroundColor Red
        exit 1
    }

    $idx = 0
    do {
        $choice = Read-Host "请输入要处理的需求文件序号 (1-$fileCount)"
    } while (-not [int]::TryParse($choice, [ref]$idx) -or $idx -lt 1 -or $idx -gt $fileCount)

    $selectedFile = $files[$idx - 1].Name

    Write-Host ""
    Write-Host "选择的需求文件: $selectedFile" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "选择模型类型:" -ForegroundColor Yellow
    Write-Host "  1. DeepSeek (在线API)" -ForegroundColor Gray
    Write-Host "  2. Ollama (本地模型)" -ForegroundColor Gray

    $modelIdx = 0
    do {
        $modelChoice = Read-Host "请输入模型序号 (1-2)"
    } while (-not [int]::TryParse($modelChoice, [ref]$modelIdx) -or $modelIdx -lt 1 -or $modelIdx -gt 2)

    $model = "DEEPSEEK"
    if ($modelIdx -eq 2) {
        $model = "OLLAMA"
    }

    Write-Host ""
    Write-Host "开始生成AADL架构..." -ForegroundColor Yellow
    Write-Host "文件: $selectedFile" -ForegroundColor Gray
    Write-Host "模型: $model" -ForegroundColor Gray
    Write-Host ""

    $bodyObj = @{
        fileName = $selectedFile
        model = $model
    }
    $body = $bodyObj | ConvertTo-Json -Depth 10

    try {
        $webClient = New-Object System.Net.WebClient
        $webClient.Encoding = [System.Text.Encoding]::UTF8
        $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
        $resultJson = $webClient.UploadString("$apiUrl/generate-architecture", "POST", $body)
        $result = $resultJson | ConvertFrom-Json
        
        Write-Host ""
        if ($result.success) {
            Write-Host "AADL架构生成成功!" -ForegroundColor Green
            Write-Host "  输出文件: output/architecture/$($result.outputFile)" -ForegroundColor Cyan
            Write-Host "  耗时: $($result.executionTime)ms" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        } else {
            Write-Host "AADL架构生成失败!" -ForegroundColor Red
            Write-Host "  错误: $($result.message)" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        }
    } catch {
        Write-Host ""
        Write-Host "请求失败: $_" -ForegroundColor Red
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Cyan
    }
} elseif ($actionIdx -eq 3) {
    $requirementsDir = ".\output\requirements"
    $architectureDir = ".\output\architecture"
    
    Write-Host ""
    Write-Host "获取可用的需求分析结果..." -ForegroundColor Yellow
    try {
        $reqFiles = Get-ChildItem -Path $requirementsDir -File | Where-Object {
            $_.Extension -eq ".json"
        } | Select-Object Name, FullName
        
        $reqCount = @($reqFiles).Count
        if ($reqCount -eq 0) {
            Write-Host "output/requirements目录下没有找到需求分析结果文件" -ForegroundColor Red
            Write-Host "请先运行需求分析(操作1)" -ForegroundColor Gray
            exit 1
        }
        
        Write-Host "找到 $reqCount 个需求文件:" -ForegroundColor Green
        for ($i = 0; $i -lt $reqCount; $i++) {
            Write-Host "  $($i + 1). $($reqFiles[$i].Name)" -ForegroundColor Gray
        }
        Write-Host ""
    } catch {
        Write-Host "获取需求文件列表失败: $_" -ForegroundColor Red
        exit 1
    }

    $reqIdx = 0
    do {
        $choice = Read-Host "请输入需求文件序号 (1-$reqCount)"
    } while (-not [int]::TryParse($choice, [ref]$reqIdx) -or $reqIdx -lt 1 -or $reqIdx -gt $reqCount)

    $selectedReqFile = $reqFiles[$reqIdx - 1].Name

    Write-Host ""
    Write-Host "选择的需求文件: $selectedReqFile" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "获取可用的架构文件..." -ForegroundColor Yellow
    try {
        $archFiles = Get-ChildItem -Path $architectureDir -File | Where-Object {
            $_.Extension -eq ".json" -and $_.Name -like "*-architecture.json"
        } | Select-Object Name, FullName
        
        $archCount = @($archFiles).Count
        if ($archCount -eq 0) {
            Write-Host "output/architecture目录下没有找到架构文件" -ForegroundColor Red
            Write-Host "请先运行架构生成(操作2)" -ForegroundColor Gray
            exit 1
        }
        
        Write-Host "找到 $archCount 个架构文件:" -ForegroundColor Green
        for ($i = 0; $i -lt $archCount; $i++) {
            Write-Host "  $($i + 1). $($archFiles[$i].Name)" -ForegroundColor Gray
        }
        Write-Host ""
    } catch {
        Write-Host "获取架构文件列表失败: $_" -ForegroundColor Red
        exit 1
    }

    $archIdx = 0
    do {
        $choice = Read-Host "请输入架构文件序号 (1-$archCount)"
    } while (-not [int]::TryParse($choice, [ref]$archIdx) -or $archIdx -lt 1 -or $archIdx -gt $archCount)

    $selectedArchFile = $archFiles[$archIdx - 1].Name

    Write-Host ""
    Write-Host "选择的架构文件: $selectedArchFile" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "选择模型类型:" -ForegroundColor Yellow
    Write-Host "  1. DeepSeek (在线API)" -ForegroundColor Gray
    Write-Host "  2. Ollama (本地模型)" -ForegroundColor Gray

    $modelIdx = 0
    do {
        $modelChoice = Read-Host "请输入模型序号 (1-2)"
    } while (-not [int]::TryParse($modelChoice, [ref]$modelIdx) -or $modelIdx -lt 1 -or $modelIdx -gt 2)

    $model = "DEEPSEEK"
    if ($modelIdx -eq 2) {
        $model = "OLLAMA"
    }

    Write-Host ""
    Write-Host "开始模块分析..." -ForegroundColor Yellow
    Write-Host "需求文件: $selectedReqFile" -ForegroundColor Gray
    Write-Host "架构文件: $selectedArchFile" -ForegroundColor Gray
    Write-Host "模型: $model" -ForegroundColor Gray
    Write-Host ""

    $bodyObj = @{
        requirementsFile = $selectedReqFile
        architectureFile = $selectedArchFile
        model = $model
    }
    $body = $bodyObj | ConvertTo-Json -Depth 10

    try {
        $webClient = New-Object System.Net.WebClient
        $webClient.Encoding = [System.Text.Encoding]::UTF8
        $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
        $resultJson = $webClient.UploadString("$apiUrl/analyze-modules", "POST", $body)
        $result = $resultJson | ConvertFrom-Json
        
        Write-Host ""
        if ($result.success) {
            Write-Host "模块分析成功!" -ForegroundColor Green
            Write-Host "  输出文件: output/modules/$($result.outputFile)" -ForegroundColor Cyan
            Write-Host "  耗时: $($result.executionTime)ms" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        } else {
            Write-Host "模块分析失败!" -ForegroundColor Red
            Write-Host "  错误: $($result.message)" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        }
    } catch {
        Write-Host ""
        Write-Host "请求失败: $_" -ForegroundColor Red
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Cyan
    }
} elseif ($actionIdx -eq 4) {
    $architectureDir = ".\output\architecture"
    $modulesDir = ".\output\modules"
    
    Write-Host ""
    Write-Host "获取可用的架构文件..." -ForegroundColor Yellow
    try {
        $archFiles = Get-ChildItem -Path $architectureDir -File | Where-Object {
            $_.Extension -eq ".json" -and $_.Name -like "*-architecture.json"
        } | Select-Object Name, FullName
        
        $archCount = @($archFiles).Count
        if ($archCount -eq 0) {
            Write-Host "output/architecture目录下没有找到架构文件" -ForegroundColor Red
            Write-Host "请先运行架构生成(操作2)" -ForegroundColor Gray
            exit 1
        }
        
        Write-Host "找到 $archCount 个架构文件:" -ForegroundColor Green
        for ($i = 0; $i -lt $archCount; $i++) {
            Write-Host "  $($i + 1). $($archFiles[$i].Name)" -ForegroundColor Gray
        }
        Write-Host ""
    } catch {
        Write-Host "获取架构文件列表失败: $_" -ForegroundColor Red
        exit 1
    }

    $archIdx = 0
    do {
        $choice = Read-Host "请输入架构文件序号 (1-$archCount)"
    } while (-not [int]::TryParse($choice, [ref]$archIdx) -or $archIdx -lt 1 -or $archIdx -gt $archCount)

    $selectedArchFile = $archFiles[$archIdx - 1].Name

    Write-Host ""
    Write-Host "选择的架构文件: $selectedArchFile" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "获取可用的模块分析文件..." -ForegroundColor Yellow
    try {
        $moduleFiles = Get-ChildItem -Path $modulesDir -File | Where-Object {
            $_.Extension -eq ".json" -and $_.Name -like "*-modules.json"
        } | Select-Object Name, FullName
        
        $moduleCount = @($moduleFiles).Count
        if ($moduleCount -eq 0) {
            Write-Host "output/modules目录下没有找到模块分析文件" -ForegroundColor Red
            Write-Host "请先运行模块分析(操作3)" -ForegroundColor Gray
            exit 1
        }
        
        Write-Host "找到 $moduleCount 个模块分析文件:" -ForegroundColor Green
        for ($i = 0; $i -lt $moduleCount; $i++) {
            Write-Host "  $($i + 1). $($moduleFiles[$i].Name)" -ForegroundColor Gray
        }
        Write-Host ""
    } catch {
        Write-Host "获取模块分析文件列表失败: $_" -ForegroundColor Red
        exit 1
    }

    $moduleIdx = 0
    do {
        $choice = Read-Host "请输入模块分析文件序号 (1-$moduleCount)"
    } while (-not [int]::TryParse($choice, [ref]$moduleIdx) -or $moduleIdx -lt 1 -or $moduleIdx -gt $moduleCount)

    $selectedModuleFile = $moduleFiles[$moduleIdx - 1].Name

    Write-Host ""
    Write-Host "选择的模块分析文件: $selectedModuleFile" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "选择模型类型:" -ForegroundColor Yellow
    Write-Host "  1. DeepSeek (在线API)" -ForegroundColor Gray
    Write-Host "  2. Ollama (本地模型)" -ForegroundColor Gray

    $modelIdx = 0
    do {
        $modelChoice = Read-Host "请输入模型序号 (1-2)"
    } while (-not [int]::TryParse($modelChoice, [ref]$modelIdx) -or $modelIdx -lt 1 -or $modelIdx -gt 2)

    $model = "DEEPSEEK"
    if ($modelIdx -eq 2) {
        $model = "OLLAMA"
    }

    Write-Host ""
    Write-Host "开始生成AADL模型..." -ForegroundColor Yellow
    Write-Host "架构文件: $selectedArchFile" -ForegroundColor Gray
    Write-Host "模块文件: $selectedModuleFile" -ForegroundColor Gray
    Write-Host "模型: $model" -ForegroundColor Gray
    Write-Host ""

    $bodyObj = @{
        architectureFile = $selectedArchFile
        modulesFile = $selectedModuleFile
        model = $model
    }
    $body = $bodyObj | ConvertTo-Json -Depth 10

    try {
        $webClient = New-Object System.Net.WebClient
        $webClient.Encoding = [System.Text.Encoding]::UTF8
        $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
        $resultJson = $webClient.UploadString("$apiUrl/generate-aadl", "POST", $body)
        $result = $resultJson | ConvertFrom-Json
        
        Write-Host ""
        if ($result.success) {
            Write-Host "AADL模型生成成功!" -ForegroundColor Green
            Write-Host "  输出文件: output/aadl/$($result.outputFile)" -ForegroundColor Cyan
            Write-Host "  耗时: $($result.executionTime)ms" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        } else {
            Write-Host "AADL模型生成失败!" -ForegroundColor Red
            Write-Host "  错误: $($result.message)" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        }
    } catch {
        Write-Host ""
        Write-Host "请求失败: $_" -ForegroundColor Red
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Cyan
    }
} elseif ($actionIdx -eq 5) {
    $aadlDir = ".\output\aadl"
    
    Write-Host ""
    Write-Host "获取可用的AADL文件..." -ForegroundColor Yellow
    try {
        $aadlFiles = Get-ChildItem -Path $aadlDir -File | Where-Object {
            $_.Extension -eq ".aadl"
        } | Select-Object Name, FullName
        
        $aadlCount = @($aadlFiles).Count
        if ($aadlCount -eq 0) {
            Write-Host "output/aadl目录下没有找到AADL文件" -ForegroundColor Red
            Write-Host "请先运行AADL生成(操作4)" -ForegroundColor Gray
            exit 1
        }
        
        Write-Host "找到 $aadlCount 个AADL文件:" -ForegroundColor Green
        for ($i = 0; $i -lt $aadlCount; $i++) {
            Write-Host "  $($i + 1). $($aadlFiles[$i].Name)" -ForegroundColor Gray
        }
        Write-Host ""
    } catch {
        Write-Host "获取AADL文件列表失败: $_" -ForegroundColor Red
        exit 1
    }

    $aadlIdx = 0
    do {
        $choice = Read-Host "请输入要修复的AADL文件序号 (1-$aadlCount)"
    } while (-not [int]::TryParse($choice, [ref]$aadlIdx) -or $aadlIdx -lt 1 -or $aadlIdx -gt $aadlCount)

    $selectedAadlFile = $aadlFiles[$aadlIdx - 1].Name

    Write-Host ""
    Write-Host "选择的AADL文件: $selectedAadlFile" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "请输入检测到的语法错误（每行一个，空行结束）:" -ForegroundColor Yellow
    $errors = @()
    while ($true) {
        $line = Read-Host
        if ($line -eq "") {
            break
        }
        $errors += $line
    }
    Write-Host ""

    if ($errors.Count -eq 0) {
        Write-Host "未输入任何错误，无法进行修复" -ForegroundColor Red
        exit 1
    }

    Write-Host "选择模型类型:" -ForegroundColor Yellow
    Write-Host "  1. DeepSeek (在线API)" -ForegroundColor Gray
    Write-Host "  2. Ollama (本地模型)" -ForegroundColor Gray

    $modelIdx = 0
    do {
        $modelChoice = Read-Host "请输入模型序号 (1-2)"
    } while (-not [int]::TryParse($modelChoice, [ref]$modelIdx) -or $modelIdx -lt 1 -or $modelIdx -gt 2)

    $model = "DEEPSEEK"
    if ($modelIdx -eq 2) {
        $model = "OLLAMA"
    }

    Write-Host ""
    Write-Host "开始修复AADL语法错误..." -ForegroundColor Yellow
    Write-Host "AADL文件: $selectedAadlFile" -ForegroundColor Gray
    Write-Host "错误数量: $($errors.Count)" -ForegroundColor Gray
    Write-Host "模型: $model" -ForegroundColor Gray
    Write-Host ""

    $bodyObj = @{
        aadlFile = $selectedAadlFile
        errors = $errors
        model = $model
    }
    $body = $bodyObj | ConvertTo-Json -Depth 10

    try {
        $webClient = New-Object System.Net.WebClient
        $webClient.Encoding = [System.Text.Encoding]::UTF8
        $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
        $resultJson = $webClient.UploadString("$apiUrl/fix-aadl", "POST", $body)
        $result = $resultJson | ConvertFrom-Json
        
        Write-Host ""
        if ($result.success) {
            Write-Host "AADL语法错误修复成功!" -ForegroundColor Green
            Write-Host "  输出文件: output/aadl/$($result.outputFile)" -ForegroundColor Cyan
            Write-Host "  耗时: $($result.executionTime)ms" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        } else {
            Write-Host "AADL语法错误修复失败!" -ForegroundColor Red
            Write-Host "  错误: $($result.message)" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        }
    } catch {
        Write-Host ""
        Write-Host "请求失败: $_" -ForegroundColor Red
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Cyan
    }
} elseif ($actionIdx -eq 6) {
    while ($true) {
        Write-Host ""
        Write-Host "知识库管理:" -ForegroundColor Yellow
        Write-Host "  1. 查看知识库列表" -ForegroundColor Gray
        Write-Host "  2. 添加知识条目" -ForegroundColor Gray
        Write-Host "  3. 删除知识条目" -ForegroundColor Gray
        Write-Host "  4. 重新加载知识库" -ForegroundColor Gray
        Write-Host "  5. 返回主菜单" -ForegroundColor Gray
        
        $kbActionIdx = 0
        do {
            $kbChoice = Read-Host "请输入操作序号 (1-5)"
        } while (-not [int]::TryParse($kbChoice, [ref]$kbActionIdx) -or $kbActionIdx -lt 1 -or $kbActionIdx -gt 5)
        
        if ($kbActionIdx -eq 5) {
            break
        }
        
        try {
            $webClient = New-Object System.Net.WebClient
            $webClient.Encoding = [System.Text.Encoding]::UTF8
            
            if ($kbActionIdx -eq 1) {
                Write-Host ""
                Write-Host "获取知识库列表..." -ForegroundColor Yellow
                $resultJson = $webClient.DownloadString("$apiUrl/knowledge/bases")
                $result = $resultJson | ConvertFrom-Json
                
                if ($result.success) {
                    Write-Host "可用知识库:" -ForegroundColor Green
                    for ($i = 0; $i -lt $result.bases.Count; $i++) {
                        $base = $result.bases[$i]
                        $countJson = $webClient.DownloadString("$apiUrl/knowledge/$base/count")
                        $countResult = $countJson | ConvertFrom-Json
                        Write-Host "  $($i + 1). $base ($($countResult.count) 条)" -ForegroundColor Gray
                    }
                    
                    $baseIdx = 0
                    do {
                        $baseChoice = Read-Host "请输入知识库序号查看详情"
                    } while (-not [int]::TryParse($baseChoice, [ref]$baseIdx) -or $baseIdx -lt 1 -or $baseIdx -gt $result.bases.Count)
                    
                    $selectedBase = $result.bases[$baseIdx - 1]
                    $entriesJson = $webClient.DownloadString("$apiUrl/knowledge/$selectedBase/list")
                    $entriesResult = $entriesJson | ConvertFrom-Json
                    
                    if ($entriesResult.success -and $entriesResult.count -gt 0) {
                        Write-Host "`n$selectedBase 知识库条目:" -ForegroundColor Green
                        for ($i = 0; $i -lt $entriesResult.entries.Count; $i++) {
                            $entry = $entriesResult.entries[$i]
                            Write-Host "  $($i + 1). ID: $($entry.id), 标题: $($entry.title)" -ForegroundColor Gray
                        }
                    } else {
                        Write-Host "$selectedBase 知识库为空" -ForegroundColor Yellow
                    }
                } else {
                    Write-Host "获取列表失败: $($result.message)" -ForegroundColor Red
                }
            } elseif ($kbActionIdx -eq 2) {
                Write-Host ""
                Write-Host "选择目标知识库:" -ForegroundColor Yellow
                Write-Host "  1. requirement (需求分析)" -ForegroundColor Gray
                Write-Host "  2. architecture (架构生成)" -ForegroundColor Gray
                Write-Host "  3. module (模块分析)" -ForegroundColor Gray
                Write-Host "  4. aadl (AADL生成)" -ForegroundColor Gray
                
                $targetIdx = 0
                do {
                    $targetChoice = Read-Host "请输入序号"
                } while (-not [int]::TryParse($targetChoice, [ref]$targetIdx) -or $targetIdx -lt 1 -or $targetIdx -gt 4)
                
                $targetBase = @("requirement", "architecture", "module", "aadl")[$targetIdx - 1]
                
                $title = Read-Host "请输入知识标题"
                Write-Host "请输入知识内容（多行，空行结束）:" -ForegroundColor Yellow
                $content = ""
                while ($true) {
                    $line = Read-Host
                    if ($line -eq "") {
                        break
                    }
                    $content += $line + "`n"
                }
                
                if ($content -eq "") {
                    Write-Host "内容不能为空" -ForegroundColor Red
                    continue
                }
                
                $bodyObj = @{
                    title = $title
                    content = $content.Trim()
                }
                $body = $bodyObj | ConvertTo-Json -Depth 10
                $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
                $resultJson = $webClient.UploadString("$apiUrl/knowledge/$targetBase/add", "POST", $body)
                $result = $resultJson | ConvertFrom-Json
                
                if ($result.success) {
                    Write-Host "知识添加成功! ID: $($result.entry.id)" -ForegroundColor Green
                } else {
                    Write-Host "添加失败: $($result.message)" -ForegroundColor Red
                }
            } elseif ($kbActionIdx -eq 3) {
                Write-Host ""
                Write-Host "选择目标知识库:" -ForegroundColor Yellow
                Write-Host "  1. requirement (需求分析)" -ForegroundColor Gray
                Write-Host "  2. architecture (架构生成)" -ForegroundColor Gray
                Write-Host "  3. module (模块分析)" -ForegroundColor Gray
                Write-Host "  4. aadl (AADL生成)" -ForegroundColor Gray
                
                $targetIdx = 0
                do {
                    $targetChoice = Read-Host "请输入序号"
                } while (-not [int]::TryParse($targetChoice, [ref]$targetIdx) -or $targetIdx -lt 1 -or $targetIdx -gt 4)
                
                $targetBase = @("requirement", "architecture", "module", "aadl")[$targetIdx - 1]
                
                $entriesJson = $webClient.DownloadString("$apiUrl/knowledge/$targetBase/list")
                $entriesResult = $entriesJson | ConvertFrom-Json
                
                if (-not $entriesResult.success -or $entriesResult.count -eq 0) {
                    Write-Host "$targetBase 知识库为空" -ForegroundColor Yellow
                    continue
                }
                
                Write-Host "选择要删除的条目:" -ForegroundColor Yellow
                for ($i = 0; $i -lt $entriesResult.entries.Count; $i++) {
                    $entry = $entriesResult.entries[$i]
                    Write-Host "  $($i + 1). ID: $($entry.id), 标题: $($entry.title)" -ForegroundColor Gray
                }
                
                $delIdx = 0
                do {
                    $delChoice = Read-Host "请输入序号"
                } while (-not [int]::TryParse($delChoice, [ref]$delIdx) -or $delIdx -lt 1 -or $delIdx -gt $entriesResult.count)
                
                $entryId = $entriesResult.entries[$delIdx - 1].id
                
                $deleteUrl = "$apiUrl/knowledge/$targetBase/$entryId"
                $deleteRequest = [System.Net.WebRequest]::Create($deleteUrl)
                $deleteRequest.Method = "DELETE"
                $deleteResponse = $deleteRequest.GetResponse()
                $deleteResponse.Close()
                
                Write-Host "知识条目删除成功: $entryId" -ForegroundColor Green
            } elseif ($kbActionIdx -eq 4) {
                Write-Host ""
                Write-Host "选择要重新加载的知识库:" -ForegroundColor Yellow
                Write-Host "  1. requirement (需求分析)" -ForegroundColor Gray
                Write-Host "  2. architecture (架构生成)" -ForegroundColor Gray
                Write-Host "  3. module (模块分析)" -ForegroundColor Gray
                Write-Host "  4. aadl (AADL生成)" -ForegroundColor Gray
                Write-Host "  5. 全部" -ForegroundColor Gray
                
                $targetIdx = 0
                do {
                    $targetChoice = Read-Host "请输入序号"
                } while (-not [int]::TryParse($targetChoice, [ref]$targetIdx) -or $targetIdx -lt 1 -or $targetIdx -gt 5)
                
                $basesToReload = if ($targetIdx -eq 5) {
                    @("requirement", "architecture", "module", "aadl")
                } else {
                    @(@("requirement", "architecture", "module", "aadl")[$targetIdx - 1])
                }
                
                foreach ($targetBase in $basesToReload) {
                    Write-Host "重新加载 $targetBase 知识库..." -ForegroundColor Yellow
                    $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
                    $resultJson = $webClient.UploadString("$apiUrl/knowledge/$targetBase/reload", "POST", "{}")
                    $result = $resultJson | ConvertFrom-Json
                    
                    if ($result.success) {
                        Write-Host "  $targetBase 加载成功! ($($result.count) 条)" -ForegroundColor Green
                    } else {
                        Write-Host "  $targetBase 加载失败: $($result.message)" -ForegroundColor Red
                    }
                }
            }
            
        } catch {
            Write-Host ""
            Write-Host "请求失败: $_" -ForegroundColor Red
        }
        
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Cyan
    }
}