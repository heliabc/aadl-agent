#!/bin/bash
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# 颜色常量
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
GRAY='\033[1;30m'
NC='\033[0m'

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}    AADL 需求分析工具${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

apiUrl="http://localhost:8080/api/requirement"

# 健康检测
echo -e "${YELLOW}正在检测服务状态...${NC}"
if curl --silent --max-time 5 "${apiUrl}/health" > /tmp/health.tmp; then
    resp=$(cat /tmp/health.tmp)
    agent=$(echo "$resp" | jq -r '.agent')
    echo -e "${GREEN}服务运行正常${NC}"
    echo -e "${GRAY}  Agent: ${agent}${NC}"
    echo ""
else
    echo -e "${RED}服务未启动或无法访问${NC}"
    echo -e "${GRAY}  请先运行: mvn spring-boot:run${NC}"
    rm -f /tmp/health.tmp
    exit 1
fi
rm -f /tmp/health.tmp

# 数字输入校验函数
read_valid_num() {
    local prompt="$1"
    local min="$2"
    local max="$3"
    local num
    while true; do
        read -p "$prompt" num
        if [[ "$num" =~ ^[0-9]+$ && $num -ge $min && $num -le $max ]]; then
            echo "$num"
            return
        fi
    done
}

# 主菜单
echo -e "${YELLOW}请选择操作:${NC}"
echo -e "${GRAY}  1. 需求分析 (Agent1: 需求条目化)${NC}"
echo -e "${GRAY}  2. 架构生成 (Agent2: AADL架构生成)${NC}"
echo -e "${GRAY}  3. 模块分析 (Agent3: 功能模块分析)${NC}"
echo -e "${GRAY}  4. AADL生成 (Agent4: AADL模型生成)${NC}"
echo -e "${GRAY}  5. AADL修复 (修复语法错误)${NC}"
echo -e "${GRAY}  6. 知识库管理${NC}"
actionIdx=$(read_valid_num "请输入操作序号 (1-6): " 1 6)

# 1. 需求分析
if [ "$actionIdx" -eq 1 ]; then
    inputDir="./input"
    echo ""
    echo -e "${YELLOW}获取可用文件列表...${NC}"
    files=()
    while IFS= read -r f; do files+=("$f"); done < <(find "$inputDir" -maxdepth 1 -type f -regex ".*\.\(doc\|docx\|txt\)" | sort)
    fileCount=${#files[@]}
    if [ "$fileCount" -eq 0 ]; then
        echo -e "${RED}input目录下没有找到可处理的文件${NC}"
        exit 1
    fi
    echo -e "${GREEN}找到 ${fileCount} 个文件:${NC}"
    for ((i=0; i<fileCount; i++)); do echo -e "${GRAY}  $((i+1)). $(basename "${files[$i]}")${NC}"; done
    echo ""
    idx=$(read_valid_num "请输入要处理的文件序号 (1-$fileCount): " 1 "$fileCount")
    selectedFile=$(basename "${files[$((idx-1))]}")
    echo ""
    echo -e "${CYAN}选择的文件: $selectedFile${NC}"
    echo ""

    echo -e "${YELLOW}选择模型类型:${NC}"
    echo -e "${GRAY}  1. DeepSeek (在线API)${NC}"
    echo -e "${GRAY}  2. Ollama (本地模型)${NC}"
    modelIdx=$(read_valid_num "请输入模型序号 (1-2): " 1 2)
    model="DEEPSEEK"
    [ "$modelIdx" -eq 2 ] && model="OLLAMA"

    echo ""
    echo -e "${YELLOW}开始需求分析...${NC}"
    echo -e "${GRAY}文件: $selectedFile${NC}"
    echo -e "${GRAY}模型: $model${NC}"
    echo ""
    body=$(jq -n --arg fn "$selectedFile" --arg m "$model" '{fileName:$fn, model:$m}')
    res=$(curl --silent -X POST -H "Content-Type: application/json; charset=utf-8" -d "$body" "${apiUrl}/process-file")
    success=$(echo "$res" | jq -r '.success')
    echo ""
    if [ "$success" = "true" ]; then
        outFile=$(echo "$res" | jq -r '.outputFile')
        execTime=$(echo "$res" | jq -r '.executionTime')
        echo -e "${GREEN}需求分析成功!${NC}"
        echo -e "${CYAN}  输出文件: output/requirements/$outFile${NC}"
        echo -e "${GRAY}  耗时: ${execTime}ms${NC}"
    else
        err=$(echo "$res" | jq -r '.error')
        echo -e "${RED}需求分析失败!${NC}"
        echo -e "${GRAY}  错误: $err${NC}"
    fi
    echo ""
    echo -e "${CYAN}========================================${NC}"

# 2. 架构生成
elif [ "$actionIdx" -eq 2 ]; then
    requirementsDir="./output/requirements"
    echo ""
    echo -e "${YELLOW}获取可用的需求分析结果...${NC}"
    files=()
    while IFS= read -r f; do files+=("$f"); done < <(find "$requirementsDir" -maxdepth 1 -type f -name "*.json" | sort)
    fileCount=${#files[@]}
    if [ "$fileCount" -eq 0 ]; then
        echo -e "${RED}output/requirements目录下没有找到需求分析结果文件${NC}"
        echo -e "${GRAY}请先运行需求分析(操作1)${NC}"
        exit 1
    fi
    echo -e "${GREEN}找到 ${fileCount} 个需求文件:${NC}"
    for ((i=0; i<fileCount; i++)); do echo -e "${GRAY}  $((i+1)). $(basename "${files[$i]}")${NC}"; done
    echo ""
    idx=$(read_valid_num "请输入要处理的需求文件序号 (1-$fileCount): " 1 "$fileCount")
    selectedFile=$(basename "${files[$((idx-1))]}")
    echo ""
    echo -e "${CYAN}选择的需求文件: $selectedFile${NC}"
    echo ""

    echo -e "${YELLOW}选择模型类型:${NC}"
    echo -e "${GRAY}  1. DeepSeek (在线API)${NC}"
    echo -e "${GRAY}  2. Ollama (本地模型)${NC}"
    modelIdx=$(read_valid_num "请输入模型序号 (1-2): " 1 2)
    model="DEEPSEEK"
    [ "$modelIdx" -eq 2 ] && model="OLLAMA"

    echo ""
    echo -e "${YELLOW}开始生成AADL架构...${NC}"
    echo -e "${GRAY}文件: $selectedFile${NC}"
    echo -e "${GRAY}模型: $model${NC}"
    echo ""
    body=$(jq -n --arg fn "$selectedFile" --arg m "$model" '{fileName:$fn, model:$m}')
    res=$(curl --silent -X POST -H "Content-Type: application/json; charset=utf-8" -d "$body" "${apiUrl}/generate-architecture")
    success=$(echo "$res" | jq -r '.success')
    echo ""
    if [ "$success" = "true" ]; then
        outFile=$(echo "$res" | jq -r '.outputFile')
        execTime=$(echo "$res" | jq -r '.executionTime')
        echo -e "${GREEN}AADL架构生成成功!${NC}"
        echo -e "${CYAN}  输出文件: output/architecture/$outFile${NC}"
        echo -e "${GRAY}  耗时: ${execTime}ms${NC}"
    else
        msg=$(echo "$res" | jq -r '.message')
        echo -e "${RED}AADL架构生成失败!${NC}"
        echo -e "${GRAY}  错误: $msg${NC}"
    fi
    echo ""
    echo -e "${CYAN}========================================${NC}"

#3. 模块分析
elif [ "$actionIdx" -eq 3 ]; then
    requirementsDir="./output/requirements"
    architectureDir="./output/architecture"
    echo ""
    echo -e "${YELLOW}获取可用的需求分析结果...${NC}"
    reqFiles=()
    while IFS= read -r f; do reqFiles+=("$f"); done < <(find "$requirementsDir" -maxdepth 1 -type f -name "*.json" | sort)
    reqCount=${#reqFiles[@]}
    if [ "$reqCount" -eq 0 ]; then
        echo -e "${RED}output/requirements目录下没有找到需求分析结果文件${NC}"
        echo -e "${GRAY}请先运行需求分析(操作1)${NC}"
        exit 1
    fi
    echo -e "${GREEN}找到 ${reqCount} 个需求文件:${NC}"
    for ((i=0; i<reqCount; i++)); do echo -e "${GRAY}  $((i+1)). $(basename ${reqFiles[$i]})${NC}"; done
    echo ""
    reqIdx=$(read_valid_num "请输入需求文件序号 (1-$reqCount): " 1 "$reqCount")
    selectedReqFile=$(basename "${reqFiles[$((reqIdx-1))]}")

    echo ""
    echo -e "${YELLOW}获取可用的架构文件...${NC}"
    archFiles=()
    while IFS= read -r f; do archFiles+=("$f"); done < <(find "$architectureDir" -maxdepth 1 -type f -name "*-architecture.json" | sort)
    archCount=${#archFiles[@]}
    if [ "$archCount" -eq 0 ]; then
        echo -e "${RED}output/architecture目录下没有找到架构文件${NC}"
        echo -e "${GRAY}请先运行架构生成(操作2)${NC}"
        exit 1
    fi
    echo -e "${GREEN}找到 ${archCount} 个架构文件:${NC}"
    for ((i=0; i<archCount; i++)); do echo -e "${GRAY}  $((i+1)). $(basename ${archFiles[$i]})${NC}"; done
    echo ""
    archIdx=$(read_valid_num "请输入架构文件序号 (1-$archCount): " 1 "$archCount")
    selectedArchFile=$(basename "${archFiles[$((archIdx-1))]}")
    echo ""
    echo -e "${CYAN}选择的需求文件: $selectedReqFile${NC}"
    echo -e "${CYAN}选择的架构文件: $selectedArchFile${NC}"
    echo ""

    echo -e "${YELLOW}选择模型类型:${NC}"
    echo -e "${GRAY}  1. DeepSeek (在线API)${NC}"
    echo -e "${GRAY}  2. Ollama (本地模型)${NC}"
    modelIdx=$(read_valid_num "请输入模型序号 (1-2): " 1 2)
    model="DEEPSEEK"
    [ "$modelIdx" -eq 2 ] && model="OLLAMA"

    echo ""
    echo -e "${YELLOW}开始模块分析...${NC}"
    echo -e "${GRAY}需求文件: $selectedReqFile${NC}"
    echo -e "${GRAY}架构文件: $selectedArchFile${NC}"
    echo -e "${GRAY}模型: $model${NC}"
    echo ""
    body=$(jq -n --arg rf "$selectedReqFile" --arg af "$selectedArchFile" --arg m "$model" '{requirementsFile:$rf, architectureFile:$af, model:$m}')
    res=$(curl --silent -X POST -H "Content-Type: application/json; charset=utf-8" -d "$body" "${apiUrl}/analyze-modules")
    success=$(echo "$res" | jq -r '.success')
    echo ""
    if [ "$success" = "true" ]; then
        outFile=$(echo "$res" | jq -r '.outputFile')
        execTime=$(echo "$res" | jq -r '.executionTime')
        echo -e "${GREEN}模块分析成功!${NC}"
        echo -e "${CYAN}  输出文件: output/modules/$outFile${NC}"
        echo -e "${GRAY}  耗时: ${execTime}ms${NC}"
    else
        msg=$(echo "$res" | jq -r '.message')
        echo -e "${RED}模块分析失败!${NC}"
        echo -e "${GRAY}  错误: $msg${NC}"
    fi
    echo ""
    echo -e "${CYAN}========================================${NC}"

#4. AADL生成
elif [ "$actionIdx" -eq 4 ]; then
    architectureDir="./output/architecture"
    modulesDir="./output/modules"
    echo ""
    echo -e "${YELLOW}获取可用的架构文件...${NC}"
    archFiles=()
    while IFS= read -r f; do archFiles+=("$f"); done < <(find "$architectureDir" -maxdepth 1 -type f -name "*-architecture.json" | sort)
    archCount=${#archFiles[@]}
    if [ "$archCount" -eq 0 ]; then
        echo -e "${RED}output/architecture目录下没有找到架构文件${NC}"
        echo -e "${GRAY}请先运行架构生成(操作2)${NC}"
        exit 1
    fi
    echo -e "${GREEN}找到 ${archCount} 个架构文件:${NC}"
    for ((i=0; i<archCount; i++)); do echo -e "${GRAY}  $((i+1)). $(basename ${archFiles[$i]})${NC}"; done
    echo ""
    archIdx=$(read_valid_num "请输入架构文件序号 (1-$archCount): " 1 "$archCount")
    selectedArchFile=$(basename "${archFiles[$((archIdx-1))]}")

    echo ""
    echo -e "${YELLOW}获取可用的模块分析文件...${NC}"
    moduleFiles=()
    while IFS= read -r f; do moduleFiles+=("$f"); done < <(find "$modulesDir" -maxdepth 1 -type f -name "*-modules.json" | sort)
    moduleCount=${#moduleFiles[@]}
    if [ "$moduleCount" -eq 0 ]; then
        echo -e "${RED}output/modules目录下没有找到模块分析文件${NC}"
        echo -e "${GRAY}请先运行模块分析(操作3)${NC}"
        exit 1
    fi
    echo -e "${GREEN}找到 ${moduleCount} 个模块分析文件:${NC}"
    for ((i=0; i<moduleCount; i++)); do echo -e "${GRAY}  $((i+1)). $(basename ${moduleFiles[$i]})${NC}"; done
    echo ""
    moduleIdx=$(read_valid_num "请输入模块分析文件序号 (1-$moduleCount): " 1 "$moduleCount")
    selectedModuleFile=$(basename "${moduleFiles[$((moduleIdx-1))]}")
    echo ""
    echo -e "${CYAN}选择的架构文件: $selectedArchFile${NC}"
    echo -e "${CYAN}选择的模块文件: $selectedModuleFile${NC}"
    echo ""

    echo -e "${YELLOW}选择模型类型:${NC}"
    echo -e "${GRAY}  1. DeepSeek (在线API)${NC}"
    echo -e "${GRAY}  2. Ollama (本地模型)${NC}"
    modelIdx=$(read_valid_num "请输入模型序号 (1-2): " 1 2)
    model="DEEPSEEK"
    [ "$modelIdx" -eq 2 ] && model="OLLAMA"

    echo ""
    echo -e "${YELLOW}开始生成AADL模型...${NC}"
    echo -e "${GRAY}架构文件: $selectedArchFile${NC}"
    echo -e "${GRAY}模块文件: $selectedModuleFile${NC}"
    echo -e "${GRAY}模型: $model${NC}"
    echo ""
    body=$(jq -n --arg af "$selectedArchFile" --arg mf "$selectedModuleFile" --arg m "$model" '{architectureFile:$af, modulesFile:$mf, model:$m}')
    res=$(curl --silent -X POST -H "Content-Type: application/json; charset=utf-8" -d "$body" "${apiUrl}/generate-aadl")
    success=$(echo "$res" | jq -r '.success')
    echo ""
    if [ "$success" = "true" ]; then
        outFile=$(echo "$res" | jq -r '.outputFile')
        execTime=$(echo "$res" | jq -r '.executionTime')
        echo -e "${GREEN}AADL模型生成成功!${NC}"
        echo -e "${CYAN}  输出文件: output/aadl/$outFile${NC}"
        echo -e "${GRAY}  耗时: ${execTime}ms${NC}"
    else
        msg=$(echo "$res" | jq -r '.message')
        echo -e "${RED}AADL模型生成失败!${NC}"
        echo -e "${GRAY}  错误: $msg${NC}"
    fi
    echo ""
    echo -e "${CYAN}========================================${NC}"

#5. AADL修复（新增逻辑，调用/fix-aadl）
elif [ "$actionIdx" -eq 5 ]; then
    aadlDir="./output/aadl"
    echo ""
    echo -e "${YELLOW}获取可用的AADL文件...${NC}"
    aadlFiles=()
    while IFS= read -r f; do aadlFiles+=("$f"); done < <(find "$aadlDir" -maxdepth 1 -type f -name "*.aadl" | sort)
    aadlCount=${#aadlFiles[@]}
    if [ "$aadlCount" -eq 0 ]; then
        echo -e "${RED}output/aadl目录下没有找到AADL文件${NC}"
        echo -e "${GRAY}请先运行AADL生成(操作4)${NC}"
        exit 1
    fi
    echo -e "${GREEN}找到 ${aadlCount} 个AADL文件:${NC}"
    for ((i=0; i<aadlCount; i++)); do echo -e "${GRAY}  $((i+1)). $(basename "${aadlFiles[$i]}")${NC}"; done
    echo ""
    aadlIdx=$(read_valid_num "请输入要修复的AADL文件序号 (1-$aadlCount): " 1 "$aadlCount")
    selectedAadlFile=$(basename "${aadlFiles[$((aadlIdx-1))]}")
    echo ""
    echo -e "${CYAN}选择的AADL文件: $selectedAadlFile${NC}"
    echo ""

    echo -e "${YELLOW}请输入检测到的语法错误（每行一个，空行结束）:${NC}"
    errors=()
    while true; do
        read line
        if [ -z "$line" ]; then break; fi
        errors+=("$line")
    done
    echo ""
    if [ ${#errors[@]} -eq 0 ]; then
        echo -e "${RED}未输入任何错误，无法进行修复${NC}"
        exit 1
    fi

    echo -e "${YELLOW}选择模型类型:${NC}"
    echo -e "${GRAY}  1. DeepSeek (在线API)${NC}"
    echo -e "${GRAY}  2. Ollama (本地模型)${NC}"
    modelIdx=$(read_valid_num "请输入模型序号 (1-2): " 1 2)
    model="DEEPSEEK"
    [ "$modelIdx" -eq 2 ] && model="OLLAMA"

    echo ""
    echo -e "${YELLOW}开始修复AADL语法错误...${NC}"
    echo -e "${GRAY}AADL文件: $selectedAadlFile${NC}"
    echo -e "${GRAY}错误数量: ${#errors[@]}${NC}"
    echo -e "${GRAY}模型: $model${NC}"
    echo ""
    errJson=$(printf '%s\n' "${errors[@]}" | jq -R . | jq -s .)
    body=$(jq -n \
        --arg af "$selectedAadlFile" \
        --argjson errs "$errJson" \
        --arg m "$model" \
        '{aadlFile:$af, errors:$errs, model:$m}')
    res=$(curl --silent -X POST -H "Content-Type: application/json; charset=utf-8" -d "$body" "${apiUrl}/fix-aadl")
    success=$(echo "$res" | jq -r '.success')
    echo ""
    if [ "$success" = "true" ]; then
        outFile=$(echo "$res" | jq -r '.outputFile')
        execTime=$(echo "$res" | jq -r '.executionTime')
        echo -e "${GREEN}AADL语法错误修复成功!${NC}"
        echo -e "${CYAN}  输出文件: output/aadl/$outFile${NC}"
        echo -e "${GRAY}  耗时: ${execTime}ms${NC}"
    else
        msg=$(echo "$res" | jq -r '.message')
        echo -e "${RED}AADL语法错误修复失败!${NC}"
        echo -e "${GRAY}  错误: $msg${NC}"
    fi
    echo ""
    echo -e "${CYAN}========================================${NC}"

#6. 知识库管理
elif [ "$actionIdx" -eq 6 ]; then
    while true; do
        echo ""
        echo -e "${YELLOW}知识库管理:${NC}"
        echo -e "${GRAY}  1. 查看知识库列表${NC}"
        echo -e "${GRAY}  2. 添加知识条目${NC}"
        echo -e "${GRAY}  3. 删除知识条目${NC}"
        echo -e "${GRAY}  4. 重新加载知识库${NC}"
        echo -e "${GRAY}  5. 返回主菜单${NC}"
        kbActionIdx=$(read_valid_num "请输入操作序号 (1-5): " 1 5)
        if [ "$kbActionIdx" -eq 5 ]; then break; fi

        if [ "$kbActionIdx" -eq 1 ]; then
            echo ""
            echo -e "${YELLOW}获取知识库列表...${NC}"
            res=$(curl --silent "${apiUrl}/knowledge/bases")
            success=$(echo "$res" | jq -r '.success')
            if [ "$success" = "true" ]; then
                bases=($(echo "$res" | jq -r '.bases[]'))
                echo -e "${GREEN}可用知识库:${NC}"
                for ((i=0; i<${#bases[@]}; i++)); do
                    b="${bases[$i]}"
                    cnt=$(curl --silent "${apiUrl}/knowledge/$b/count" | jq -r '.count')
                    echo -e "${GRAY}  $((i+1)). $b ($cnt 条)${NC}"
                done
                baseIdx=$(read_valid_num "请输入知识库序号查看详情: " 1 "${#bases[@]}")
                selectedBase="${bases[$((baseIdx-1))]}"
                entriesRes=$(curl --silent "${apiUrl}/knowledge/$selectedBase/list")
                entSuccess=$(echo "$entriesRes" | jq -r '.success')
                entCount=$(echo "$entriesRes" | jq -r '.count')
                echo ""
                if [ "$entSuccess" = "true" ] && [ "$entCount" -gt 0 ]; then
                    echo -e "${GREEN}$selectedBase 知识库条目:${NC}"
                    ids=($(echo "$entriesRes" | jq -r '.entries[].id'))
                    titles=($(echo "$entriesRes" | jq -r '.entries[].title'))
                    for ((i=0; i<entCount; i++)); do
                        echo -e "${GRAY}  $((i+1)). ID: ${ids[$i]}, 标题: ${titles[$i]}${NC}"
                    done
                else
                    echo -e "${YELLOW}$selectedBase 知识库为空${NC}"
                fi
            else
                msg=$(echo "$res" | jq -r '.message')
                echo -e "${RED}获取列表失败: $msg${NC}"
            fi
        elif [ "$kbActionIdx" -eq 2 ]; then
            echo ""
            echo -e "${YELLOW}选择目标知识库:${NC}"
            echo -e "${GRAY}  1. requirement (需求分析)${NC}"
            echo -e "${GRAY}  2. architecture (架构生成)${NC}"
            echo -e "${GRAY}  3. module (模块分析)${NC}"
            echo -e "${GRAY}  4. aadl (AADL生成)${NC}"
            targetIdx=$(read_valid_num "请输入序号: " 1 4)
            basesArr=("requirement" "architecture" "module" "aadl")
            targetBase="${basesArr[$((targetIdx-1))]}"
            read -p "请输入知识标题: " title
            echo -e "${YELLOW}请输入知识内容（多行，空行结束）:${NC}"
            content=""
            while true; do
                read line
                if [ -z "$line" ]; then break; fi
                content+="$line"$'\n'
            done
            content=$(echo "$content" | sed '/^\s*$/d')
            if [ -z "$content" ]; then
                echo -e "${RED}内容不能为空${NC}"
                continue
            fi
            body=$(jq -n --arg t "$title" --arg c "$content" '{title:$t, content:$c}')
            res=$(curl --silent -X POST -H "Content-Type: application/json; charset=utf-8" -d "$body" "${apiUrl}/knowledge/$targetBase/add")
            s=$(echo "$res" | jq -r '.success')
            if [ "$s" = "true" ]; then
                eid=$(echo "$res" | jq -r '.entry.id')
                echo -e "${GREEN}知识添加成功! ID: $eid${NC}"
            else
                m=$(echo "$res" | jq -r '.message')
                echo -e "${RED}添加失败: $m${NC}"
            fi
        elif [ "$kbActionIdx" -eq 3 ]; then
            echo ""
            echo -e "${YELLOW}选择目标知识库:${NC}"
            echo -e "${GRAY}  1. requirement (需求分析)${NC}"
            echo -e "${GRAY}  2. architecture (架构生成)${NC}"
            echo -e "${GRAY}  3. module (模块分析)${NC}"
            echo -e "${GRAY}  4. aadl (AADL生成)${NC}"
            targetIdx=$(read_valid_num "请输入序号: " 1 4)
            basesArr=("requirement" "architecture" "module" "aadl")
            targetBase="${basesArr[$((targetIdx-1))]}"
            entriesRes=$(curl --silent "${apiUrl}/knowledge/$targetBase/list")
            entSuccess=$(echo "$entriesRes" | jq -r '.success')
            entCount=$(echo "$entriesRes" | jq -r '.count')
            if [ "$entSuccess" != "true" ] || [ "$entCount" -eq 0 ]; then
                echo -e "${YELLOW}$targetBase 知识库为空${NC}"
                continue
            fi
            echo -e "${YELLOW}选择要删除的条目:${NC}"
            ids=($(echo "$entriesRes" | jq -r '.entries[].id'))
            titles=($(echo "$entriesRes" | jq -r '.entries[].title'))
            for ((i=0; i<entCount; i++)); do
                echo -e "${GRAY}  $((i+1)). ID: ${ids[$i]}, 标题: ${titles[$i]}${NC}"
            done
            delIdx=$(read_valid_num "请输入序号: " 1 "$entCount")
            entryId="${ids[$((delIdx-1))]}"
            curl --silent -X DELETE "${apiUrl}/knowledge/$targetBase/$entryId" > /dev/null
            echo -e "${GREEN}知识条目删除成功: $entryId${NC}"
        elif [ "$kbActionIdx" -eq 4 ]; then
            echo ""
            echo -e "${YELLOW}选择要重新加载的知识库:${NC}"
            echo -e "${GRAY}  1. requirement (需求分析)${NC}"
            echo -e "${GRAY}  2. architecture (架构生成)${NC}"
            echo -e "${GRAY}  3. module (模块分析)${NC}"
            echo -e "${GRAY}  4. aadl (AADL生成)${NC}"
            echo -e "${GRAY}  5. 全部${NC}"
            targetIdx=$(read_valid_num "请输入序号: " 1 5)
            allBases=("requirement" "architecture" "module" "aadl")
            if [ "$targetIdx" -eq 5 ]; then
                reloadList=("${allBases[@]}")
            else
                reloadList=("${allBases[$((targetIdx-1))]}")
            fi
            for b in "${reloadList[@]}"; do
                echo -e "${YELLOW}重新加载 $b 知识库...${NC}"
                res=$(curl --silent -X POST -H "Content-Type: application/json; charset=utf-8" -d '{}' "${apiUrl}/knowledge/$b/reload")
                s=$(echo "$res" | jq -r '.success')
                cnt=$(echo "$res" | jq -r '.count')
                if [ "$s" = "true" ]; then
                    echo -e "${GREEN}  $b 加载成功! ($cnt 条)${NC}"
                else
                    m=$(echo "$res" | jq -r '.message')
                    echo -e "${RED}  $b 加载失败: $m${NC}"
                fi
            done
        fi
        echo ""
        echo -e "${CYAN}========================================${NC}"
    done
fi