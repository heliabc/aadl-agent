@echo off
REM ============================================================
REM AADL GBNF 受限解码服务启动脚本 (llama-cpp-python 版)
REM ============================================================
REM 基于 llama-cpp-python 的真正 token 级 GBNF 约束解码
REM 需要安装 llama-cpp-python 和 GGUF 模型文件

echo ========================================
echo AADL GBNF Constrained Decoding Service
echo ========================================
echo.

cd /d "%~dp0"

REM 检查 Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python 未安装或不在 PATH 中
    pause
    exit /b 1
)

REM 检查参数
if "%~1"=="" (
    echo [ERROR] 请指定 GGUF 模型文件路径
    echo.
    echo 用法: start_llamacpp_service.bat ^<模型路径^> [选项]
    echo.
    echo 选项:
    echo   --port PORT        服务端口 (默认: 8082)
    echo   --n-ctx N_CTX      上下文窗口大小 (默认: 4096)
    echo   --n-gpu-layers N   GPU 层数 (默认: -1 = 全部)
    echo.
    echo 示例:
    echo   start_llamacpp_service.bat "C:\Models\qwen3-8b.gguf"
    echo   start_llamacpp_service.bat "C:\Models\qwen3-8b.gguf" --port 8082 --n-ctx 8192
    pause
    exit /b 1
)

set MODEL_PATH=%~1
shift

REM 检查模型文件
if not exist "%MODEL_PATH%" (
    echo [ERROR] 模型文件不存在: %MODEL_PATH%
    pause
    exit /b 1
)

REM 检查依赖
echo [INFO] 检查依赖...
python -c "import llama_cpp" >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] llama-cpp-python 未安装
    echo [INFO] 正在安装 llama-cpp-python...
    echo.
    echo 注意: 如果安装失败，请手动安装:
    echo   pip install llama-cpp-python --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu121
    echo.
    pip install llama-cpp-python flask
    if %errorlevel% neq 0 (
        echo [ERROR] llama-cpp-python 安装失败
        echo 请参考 https://github.com/abetlen/llama-cpp-python 手动安装
        pause
        exit /b 1
    )
)

REM 启动服务
echo.
echo [INFO] 模型文件: %MODEL_PATH%
echo [INFO] 语法文件: grammars\aadl.gbnf
echo [INFO] 启动 GBNF 受限解码服务...
echo [INFO] 按 Ctrl+C 停止服务
echo.

python llama_cpp_gbnf_service.py --host 0.0.0.0 --port 8082 --model-path "%MODEL_PATH%" --grammar-path "grammars\aadl.gbnf" %*

pause
