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
Write-Host "  5. AADL再生 (修复语法错误后重新生成)" -ForegroundColor Gray
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
    $architectureDir = ".\output\architecture"
    $modulesDir = ".\output\modules"
    $aadlDir = ".\output\aadl"
    
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

    $aadlFileName = $selectedArchFile -replace "-architecture\.json$", ".aadl"
    $aadlFilePath = Join-Path $aadlDir $aadlFileName
    
    $previousAadl = ""
    if (Test-Path $aadlFilePath) {
        $previousAadl = Get-Content $aadlFilePath -Raw -Encoding UTF8
        Write-Host "检测到已存在的AADL文件，将作为上下文进行再生" -ForegroundColor Yellow
    } else {
        Write-Host "未检测到已存在的AADL文件，将基于架构和模块重新生成" -ForegroundColor Gray
    }
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
    Write-Host "开始AADL再生..." -ForegroundColor Yellow
    Write-Host "架构文件: $selectedArchFile" -ForegroundColor Gray
    Write-Host "模块文件: $selectedModuleFile" -ForegroundColor Gray
    Write-Host "模型: $model" -ForegroundColor Gray
    Write-Host ""

    $bodyObj = @{
        architectureFile = $selectedArchFile
        modulesFile = $selectedModuleFile
        previousAadl = $previousAadl
        errors = $errors
        model = $model
    }
    $body = $bodyObj | ConvertTo-Json -Depth 10

    try {
        $webClient = New-Object System.Net.WebClient
        $webClient.Encoding = [System.Text.Encoding]::UTF8
        $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
        $resultJson = $webClient.UploadString("$apiUrl/regenerate-aadl", "POST", $body)
        $result = $resultJson | ConvertFrom-Json
        
        Write-Host ""
        if ($result.success) {
            Write-Host "AADL模型再生成功!" -ForegroundColor Green
            Write-Host "  输出文件: output/aadl/$($result.outputFile)" -ForegroundColor Cyan
            Write-Host "  耗时: $($result.executionTime)ms" -ForegroundColor Gray
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
        } else {
            Write-Host "AADL模型再生失败!" -ForegroundColor Red
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
        Write-Host "  1. 查看知识库文档列表" -ForegroundColor Gray
        Write-Host "  2. 添加知识条目" -ForegroundColor Gray
        Write-Host "  3. 上传知识文件" -ForegroundColor Gray
        Write-Host "  4. 删除文档" -ForegroundColor Gray
        Write-Host "  5. 重新加载知识库" -ForegroundColor Gray
        Write-Host "  6. 返回主菜单" -ForegroundColor Gray
        
        $kbActionIdx = 0
        do {
            $kbChoice = Read-Host "请输入操作序号 (1-6)"
        } while (-not [int]::TryParse($kbChoice, [ref]$kbActionIdx) -or $kbActionIdx -lt 1 -or $kbActionIdx -gt 6)
        
        if ($kbActionIdx -eq 6) {
            break
        }
        
        try {
            $webClient = New-Object System.Net.WebClient
            $webClient.Encoding = [System.Text.Encoding]::UTF8
            
            if ($kbActionIdx -eq 1) {
                Write-Host ""
                Write-Host "获取知识库文档列表..." -ForegroundColor Yellow
                $resultJson = $webClient.DownloadString("$apiUrl/knowledge/list")
                $result = $resultJson | ConvertFrom-Json
                
                if ($result.success) {
                    Write-Host "知识库共有 $($result.count) 个文档:" -ForegroundColor Green
                    for ($i = 0; $i -lt $result.documents.Count; $i++) {
                        $doc = $result.documents[$i]
                        Write-Host "  $($i + 1). ID: $($doc.id), 标题: $($doc.title), 来源: $($doc.source)" -ForegroundColor Gray
                    }
                } else {
                    Write-Host "获取列表失败: $($result.message)" -ForegroundColor Red
                }
            } elseif ($kbActionIdx -eq 2) {
                Write-Host ""
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
                $resultJson = $webClient.UploadString("$apiUrl/knowledge/add", "POST", $body)
                $result = $resultJson | ConvertFrom-Json
                
                if ($result.success) {
                    Write-Host "知识添加成功! ($($result.chunkCount) 个分块)" -ForegroundColor Green
                } else {
                    Write-Host "添加失败: $($result.message)" -ForegroundColor Red
                }
            } elseif ($kbActionIdx -eq 3) {
                Write-Host ""
                $filePath = Read-Host "请输入文件路径 (.md 或 .txt)"
                
                if (-not (Test-Path $filePath)) {
                    Write-Host "文件不存在: $filePath" -ForegroundColor Red
                    continue
                }
                
                $filename = (Get-Item $filePath).Name
                if (-not ($filename.EndsWith(".md") -or $filename.EndsWith(".txt"))) {
                    Write-Host "只支持 .md 和 .txt 文件" -ForegroundColor Red
                    continue
                }
                
                $fileBytes = [System.IO.File]::ReadAllBytes($filePath)
                $boundary = "----WebKitFormBoundary" + [Guid]::NewGuid().ToString()
                $encoding = [System.Text.Encoding]::UTF8
                
                $header = "--$boundary`r`nContent-Disposition: form-data; name=`"file`"; filename=`"$filename`"`r`nContent-Type: text/plain`r`n`r`n"
                $footer = "`r`n--$boundary--"
                
                $headerBytes = $encoding.GetBytes($header)
                $footerBytes = $encoding.GetBytes($footer)
                
                $request = [System.Net.WebRequest]::Create("$apiUrl/knowledge/upload")
                $request.Method = "POST"
                $request.ContentType = "multipart/form-data; boundary=$boundary"
                
                $stream = $request.GetRequestStream()
                $stream.Write($headerBytes, 0, $headerBytes.Length)
                $stream.Write($fileBytes, 0, $fileBytes.Length)
                $stream.Write($footerBytes, 0, $footerBytes.Length)
                $stream.Close()
                
                $response = $request.GetResponse()
                $responseStream = $response.GetResponseStream()
                $reader = New-Object System.IO.StreamReader($responseStream)
                $resultJson = $reader.ReadToEnd()
                $reader.Close()
                $response.Close()
                
                $result = $resultJson | ConvertFrom-Json
                
                if ($result.success) {
                    Write-Host "文件上传成功! ($($result.chunkCount) 个分块)" -ForegroundColor Green
                } else {
                    Write-Host "上传失败: $($result.message)" -ForegroundColor Red
                }
            } elseif ($kbActionIdx -eq 4) {
                Write-Host ""
                $resultJson = $webClient.DownloadString("$apiUrl/knowledge/list")
                $result = $resultJson | ConvertFrom-Json
                
                if (-not $result.success -or $result.count -eq 0) {
                    Write-Host "知识库为空" -ForegroundColor Yellow
                    continue
                }
                
                Write-Host "选择要删除的文档:" -ForegroundColor Yellow
                for ($i = 0; $i -lt $result.documents.Count; $i++) {
                    $doc = $result.documents[$i]
                    Write-Host "  $($i + 1). ID: $($doc.id), 标题: $($doc.title)" -ForegroundColor Gray
                }
                
                $delIdx = 0
                do {
                    $delChoice = Read-Host "请输入序号"
                } while (-not [int]::TryParse($delChoice, [ref]$delIdx) -or $delIdx -lt 1 -or $delIdx -gt $result.count)
                
                $docId = $result.documents[$delIdx - 1].id
                
                $deleteUrl = "$apiUrl/knowledge/$docId"
                $deleteRequest = [System.Net.WebRequest]::Create($deleteUrl)
                $deleteRequest.Method = "DELETE"
                $deleteResponse = $deleteRequest.GetResponse()
                $deleteResponse.Close()
                
                Write-Host "文档删除成功: $docId" -ForegroundColor Green
            } elseif ($kbActionIdx -eq 5) {
                Write-Host ""
                Write-Host "重新加载知识库..." -ForegroundColor Yellow
                $webClient.Headers.Add("Content-Type", "application/json; charset=utf-8")
                $resultJson = $webClient.UploadString("$apiUrl/knowledge/reload", "POST", "{}")
                $result = $resultJson | ConvertFrom-Json
                
                if ($result.success) {
                    Write-Host "知识库重新加载成功! ($($result.count) 个文档)" -ForegroundColor Green
                } else {
                    Write-Host "加载失败: $($result.message)" -ForegroundColor Red
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