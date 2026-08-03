#!/bin/bash
#==============================================================================
# batch_generate.sh - 批量需求到AADL代码生成脚本
#
# 功能：指定一个文件夹，将其中的 .txt 需求文件依次执行完整流水线：
#   需求分析 -> 架构生成 -> 模块分析 -> AADL生成
#
# 用法：
#   ./batch_generate.sh -d <需求文件夹> [选项]
#
# 选项：
#   -d <目录>    包含 .txt 需求文件的文件夹（必填）
#   -m <模型>    使用的模型类型：OLLAMA 或 DEEPSEEK（默认 OLLAMA）
#   -s <地址>    服务器地址，如 http://localhost:8080（默认 http://localhost:8080）
#   -o <目录>    AADL 文件输出目录（默认 ./aadl_output）
#   -h           显示帮助信息
#
# 依赖：curl, jq
#
# 示例：
#   ./batch_generate.sh -d ./requirements
#   ./batch_generate.sh -d ./requirements -m DEEPSEEK -o ./aadl_files
#==============================================================================

set -uo pipefail

#------------------------------ 颜色定义 ------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

#------------------------------ 默认配置 ------------------------------
SERVER_URL="http://localhost:8080"
API_BASE="/api/requirement"
MODEL="OLLAMA"
INPUT_DIR=""
OUTPUT_DIR="./aadl_output"

# 统计计数
TOTAL=0
SUCCESS=0
FAIL=0
SKIP=0

# 日志文件
LOG_FILE="batch_generate_$(date +%Y%m%d_%H%M%S).log"
FAILED_LIST=()

#------------------------------ 函数定义 ------------------------------

print_usage() {
    cat << 'EOF'
用法: batch_generate.sh -d <需求文件夹> [选项]

必填参数:
  -d <目录>    包含 .txt 需求文件的文件夹

可选参数:
  -m <模型>    模型类型: OLLAMA 或 DEEPSEEK (默认: OLLAMA)
  -s <地址>    服务器地址 (默认: http://localhost:8080)
  -o <目录>    AADL 输出目录 (默认: ./aadl_output)
  -h           显示帮助

示例:
  ./batch_generate.sh -d ./requirements
  ./batch_generate.sh -d ./requirements -m DEEPSEEK -o ./output
EOF
}

log() {
    local level="$1"
    shift
    local msg="$*"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo -e "[$timestamp] [$level] $msg" | tee -a "$LOG_FILE"
}

log_info()    { log "INFO"    "${GREEN}$*${NC}"; }
log_warn()    { log "WARN"    "${YELLOW}$*${NC}"; }
log_error()   { log "ERROR"   "${RED}$*${NC}"; }
log_step()    { log "STEP"    "${CYAN}$*${NC}"; }
log_success() { log "SUCCESS" "${GREEN}$*${NC}"; }

check_dependencies() {
    local missing=()
    if ! command -v curl &>/dev/null; then
        missing+=("curl")
    fi
    if ! command -v jq &>/dev/null; then
        missing+=("jq")
    fi
    if [ ${#missing[@]} -gt 0 ]; then
        echo -e "${RED}错误: 缺少依赖工具: ${missing[*]}${NC}"
        echo "请安装后重试:"
        echo "  Ubuntu/Debian: sudo apt install ${missing[*]}"
        echo "  CentOS/RHEL:   sudo yum install ${missing[*]}"
        exit 1
    fi
}

check_server() {
    log_info "检查服务器连接: ${SERVER_URL}"
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 \
        "${SERVER_URL}/" 2>/dev/null) || http_code="000"

    if [ "$http_code" = "000" ]; then
        log_error "无法连接到服务器 ${SERVER_URL}，请确认服务已启动"
        exit 1
    fi
    log_success "服务器连接正常 (HTTP ${http_code})"
}

# 调用 API 并解析响应
# 参数: $1=API路径, $2=JSON请求体
# 输出: 响应体JSON
# 返回值: 0=成功, 1=失败
call_api() {
    local endpoint="$1"
    local payload="$2"
    local url="${SERVER_URL}${API_BASE}${endpoint}"
    local response
    local http_code

    # || true 防止 set -e 在 curl 失败时直接退出脚本
    response=$(curl -s -w "\n%{http_code}" \
        -X POST "$url" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        --connect-timeout 10 \
        --max-time 1800 2>&1) || true

    # 最后一行是 HTTP 状态码
    http_code=$(printf '%s' "$response" | tail -1)
    local body
    body=$(printf '%s' "$response" | sed '$d')

    if [ "$http_code" != "200" ]; then
        log_error "API 请求失败: ${endpoint}, HTTP ${http_code}"
        if [ -n "$body" ]; then
            log_error "响应: ${body}"
        fi
        return 1
    fi

    printf '%s' "$body"
}

# 处理单个文件
# 参数: $1=文件路径
# 返回值: 0=成功, 1=失败
process_file() {
    local file_path="$1"
    local file_name
    file_name=$(basename "$file_path")
    local base_name="${file_name%.txt}"

    log_step "=========================================="
    log_step "开始处理: ${file_name}"
    log_step "=========================================="

    # 检查文件是否为空
    if [ ! -s "$file_path" ]; then
        log_error "文件内容为空: ${file_path}"
        return 1
    fi

    local content_length
    content_length=$(wc -c < "$file_path")
    log_info "文件大小: ${content_length} 字节"

    # ==================== 第1步: 需求分析 ====================
    log_step "[1/4] 需求分析..."

    local analyze_payload
    analyze_payload=$(jq -n \
        --arg content "$(cat "$file_path")" \
        --arg fileName "$file_name" \
        --arg model "$MODEL" \
        '{content: $content, fileName: $fileName, model: $model}')

    local analyze_response
    if ! analyze_response=$(call_api "/analyze" "$analyze_payload"); then
        log_error "需求分析请求失败: ${file_name}"
        return 1
    fi

    local analyze_success
    analyze_success=$(echo "$analyze_response" | jq -r '.success')

    if [ "$analyze_success" != "true" ]; then
        local error_msg
        error_msg=$(echo "$analyze_response" | jq -r '.message // "未知错误"')
        log_error "需求分析失败: ${error_msg}"
        return 1
    fi

    local session_id
    session_id=$(echo "$analyze_response" | jq -r '.sessionId')
    local req_output_file
    req_output_file=$(echo "$analyze_response" | jq -r '.outputFile // "requirements_'${session_id}'.json"')
    local analyze_time
    analyze_time=$(echo "$analyze_response" | jq -r '.executionTime // 0')

    log_success "需求分析完成 (session: ${session_id}, 耗时: ${analyze_time}ms, 输出: ${req_output_file})"

    # ==================== 第2步: 架构生成 ====================
    log_step "[2/4] 架构生成..."

    local arch_payload
    arch_payload=$(jq -n \
        --arg fileName "$req_output_file" \
        --arg sessionId "$session_id" \
        --arg model "$MODEL" \
        '{fileName: $fileName, sessionId: $sessionId, model: $model}')

    local arch_response
    if ! arch_response=$(call_api "/generate-architecture" "$arch_payload"); then
        log_error "架构生成请求失败: ${file_name}"
        return 1
    fi

    local arch_success
    arch_success=$(echo "$arch_response" | jq -r '.success')

    if [ "$arch_success" != "true" ]; then
        local error_msg
        error_msg=$(echo "$arch_response" | jq -r '.message // "未知错误"')
        log_error "架构生成失败: ${error_msg}"
        return 1
    fi

    local arch_output_file
    arch_output_file=$(echo "$arch_response" | jq -r '.outputFile // "requirements_'${session_id}'-architecture.json"')
    local arch_time
    arch_time=$(echo "$arch_response" | jq -r '.executionTime // 0')

    log_success "架构生成完成 (耗时: ${arch_time}ms, 输出: ${arch_output_file})"

    # ==================== 第3步: 模块分析 ====================
    log_step "[3/4] 模块分析..."

    local module_payload
    module_payload=$(jq -n \
        --arg reqFile "$req_output_file" \
        --arg archFile "$arch_output_file" \
        --arg sessionId "$session_id" \
        --arg model "$MODEL" \
        '{requirementsFile: $reqFile, architectureFile: $archFile, sessionId: $sessionId, model: $model}')

    local module_response
    if ! module_response=$(call_api "/analyze-modules" "$module_payload"); then
        log_error "模块分析请求失败: ${file_name}"
        return 1
    fi

    local module_success
    module_success=$(echo "$module_response" | jq -r '.success')

    if [ "$module_success" != "true" ]; then
        local error_msg
        error_msg=$(echo "$module_response" | jq -r '.message // "未知错误"')
        log_error "模块分析失败: ${error_msg}"
        return 1
    fi

    local module_output_file
    module_output_file=$(echo "$module_response" | jq -r '.outputFile // "requirements_'${session_id}'-modules.json"')
    local module_time
    module_time=$(echo "$module_response" | jq -r '.executionTime // 0')

    log_success "模块分析完成 (耗时: ${module_time}ms, 输出: ${module_output_file})"

    # ==================== 第4步: AADL生成 ====================
    log_step "[4/4] AADL代码生成..."

    local aadl_payload
    aadl_payload=$(jq -n \
        --arg archFile "$arch_output_file" \
        --arg modFile "$module_output_file" \
        --arg sessionId "$session_id" \
        --arg model "$MODEL" \
        '{architectureFile: $archFile, modulesFile: $modFile, sessionId: $sessionId, model: $model}')

    local aadl_response
    if ! aadl_response=$(call_api "/generate-aadl" "$aadl_payload"); then
        log_error "AADL生成请求失败: ${file_name}"
        return 1
    fi

    local aadl_success
    aadl_success=$(echo "$aadl_response" | jq -r '.success')

    if [ "$aadl_success" != "true" ]; then
        local error_msg
        error_msg=$(echo "$aadl_response" | jq -r '.message // "未知错误"')
        log_error "AADL生成失败: ${error_msg}"
        return 1
    fi

    local aadl_output_file
    aadl_output_file=$(echo "$aadl_response" | jq -r '.outputFile // "requirements_'${session_id}'.aadl"')
    local aadl_time
    aadl_time=$(echo "$aadl_response" | jq -r '.executionTime // 0')

    # 提取 AADL 代码并保存到本地输出目录
    local aadl_data
    aadl_data=$(echo "$aadl_response" | jq -r '.data')

    local local_output_path="${OUTPUT_DIR}/${base_name}.aadl"
    echo "$aadl_data" > "$local_output_path"

    log_success "AADL生成完成 (耗时: ${aadl_time}ms, 输出: ${aadl_output_file})"
    log_success "AADL文件已保存到本地: ${local_output_path}"

    # 总耗时
    local total_time=$((analyze_time + arch_time + module_time + aadl_time))
    log_info "文件 ${file_name} 全流程完成，总耗时: ${total_time}ms"

    return 0
}

print_summary() {
    echo ""
    echo -e "${CYAN}==============================================${NC}"
    echo -e "${CYAN}              批处理执行总结                   ${NC}"
    echo -e "${CYAN}==============================================${NC}"
    echo -e "  总文件数:     ${TOTAL}"
    echo -e "  ${GREEN}成功:         ${SUCCESS}${NC}"
    echo -e "  ${RED}失败:         ${FAIL}${NC}"
    echo -e "  ${YELLOW}跳过:         ${SKIP}${NC}"
    echo -e "  日志文件:     ${LOG_FILE}"
    echo -e "  输出目录:     ${OUTPUT_DIR}"
    echo -e "${CYAN}==============================================${NC}"

    if [ ${#FAILED_LIST[@]} -gt 0 ]; then
        echo -e "\n${RED}失败文件列表:${NC}"
        for f in "${FAILED_LIST[@]}"; do
            echo -e "  ${RED}- ${f}${NC}"
        done
    fi
    echo ""
}

#------------------------------ 主流程 ------------------------------

# 解析参数
while getopts "d:m:s:o:h" opt; do
    case $opt in
        d) INPUT_DIR="$OPTARG" ;;
        m) MODEL="$OPTARG" ;;
        s) SERVER_URL="$OPTARG" ;;
        o) OUTPUT_DIR="$OPTARG" ;;
        h) print_usage; exit 0 ;;
        *) print_usage; exit 1 ;;
    esac
done

# 校验必填参数
if [ -z "$INPUT_DIR" ]; then
    echo -e "${RED}错误: 必须指定需求文件夹 (-d)${NC}"
    print_usage
    exit 1
fi

# 校验输入目录
if [ ! -d "$INPUT_DIR" ]; then
    echo -e "${RED}错误: 目录不存在: ${INPUT_DIR}${NC}"
    exit 1
fi

# 校验模型类型
MODEL=$(echo "$MODEL" | tr '[:lower:]' '[:upper:]')
if [ "$MODEL" != "OLLAMA" ] && [ "$MODEL" != "DEEPSEEK" ]; then
    echo -e "${RED}错误: 不支持的模型类型: ${MODEL} (可选: OLLAMA, DEEPSEEK)${NC}"
    exit 1
fi

# 检查依赖
check_dependencies

# 创建输出目录
mkdir -p "$OUTPUT_DIR"

# 打印配置信息
echo -e "${CYAN}========================================================${NC}"
echo -e "${CYAN}  AADL 批量生成脚本                                     ${NC}"
echo -e "${CYAN}========================================================${NC}"
echo -e "  输入目录:   ${INPUT_DIR}"
echo -e "  输出目录:   ${OUTPUT_DIR}"
echo -e "  服务器:     ${SERVER_URL}"
echo -e "  模型:       ${MODEL}"
echo -e "  日志文件:   ${LOG_FILE}"
echo -e "${CYAN}========================================================${NC}"
echo ""

# 检查服务器
check_server
echo ""

# 收集 .txt 文件
mapfile -t txt_files < <(find "$INPUT_DIR" -maxdepth 1 -name "*.txt" -type f | sort)

TOTAL=${#txt_files[@]}

if [ "$TOTAL" -eq 0 ]; then
    log_warn "在目录 ${INPUT_DIR} 中没有找到 .txt 文件"
    exit 0
fi

log_info "找到 ${TOTAL} 个 .txt 文件，开始批量处理..."
echo ""

# 逐个处理文件
for i in "${!txt_files[@]}"; do
    file_path="${txt_files[$i]}"
    file_name=$(basename "$file_path")
    file_idx=$((i + 1))

    echo -e "${BLUE}--------------------------------------------------------${NC}"
    echo -e "${BLUE}[${file_idx}/${TOTAL}] 处理文件: ${file_name}${NC}"
    echo -e "${BLUE}--------------------------------------------------------${NC}"

    if process_file "$file_path"; then
        SUCCESS=$((SUCCESS + 1))
    else
        FAIL=$((FAIL + 1))
        FAILED_LIST+=("$file_name")
        log_warn "文件 ${file_name} 处理失败，跳过，继续处理下一个文件"
    fi
    echo ""
done

# 打印总结
print_summary

exit 0
