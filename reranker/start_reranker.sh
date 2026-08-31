#!/bin/bash
# ============================================
# Cross-Encoder 重排序服务启动脚本
# 模型: BAAI/bge-reranker-v2-m3
# ============================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ======== 可配置项 ========
export RERANKER_MODEL="BAAI/bge-reranker-v2-m3"
export RERANKER_HOST="0.0.0.0"
export RERANKER_PORT="8081"
# RERANKER_DEVICE 留空则自动检测（有GPU用cuda，否则用cpu）
# export RERANKER_DEVICE="cpu"
# export RERANKER_DEVICE="cuda"

VENV_DIR="venv"

# ======== 检查Python ========
if ! command -v python3 &> /dev/null; then
    echo "[ERROR] 未找到 python3，请先安装 Python 3.8+"
    exit 1
fi

PYTHON_VERSION=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
echo "[INFO] Python 版本: $PYTHON_VERSION"

# ======== 创建虚拟环境 ========
if [ ! -d "$VENV_DIR" ]; then
    echo "[INFO] 创建虚拟环境..."
    python3 -m venv "$VENV_DIR"
    if [ $? -ne 0 ]; then
        echo "[ERROR] 虚拟环境创建失败"
        exit 1
    fi
    echo "[INFO] 虚拟环境创建成功"
fi

# ======== 激活虚拟环境 ========
source "$VENV_DIR/bin/activate"

# ======== 安装依赖 ========
echo "[INFO] 检查依赖..."
pip install -q -r requirements.txt
if [ $? -ne 0 ]; then
    echo "[ERROR] 依赖安装失败"
    exit 1
fi
echo "[INFO] 依赖检查完成"

# ======== 启动服务 ========
echo "============================================"
echo "  BGE Reranker Service"
echo "  Model:  $RERANKER_MODEL"
echo "  Address: http://$RERANKER_HOST:$RERANKER_PORT"
echo "============================================"
echo ""

python reranker_service.py
