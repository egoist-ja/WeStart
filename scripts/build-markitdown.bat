@echo off
chcp 65001 >nul
echo ============================================
echo   MarkItDown 打包脚本
echo ============================================
echo.

:: 查找或安装 Python 3.12
set PYTHON=
for /f "delims=" %%i in ('where python 2^>nul') do (
    %%i --version 2>nul | findstr "3.12" >nul
    if !errorlevel! equ 0 (
        set PYTHON=%%i
        goto :found
    )
)

:: 尝试 pip 安装目录
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" (
    set PYTHON=%LOCALAPPDATA%\Programs\Python\Python312\python.exe
    goto :found
)

echo [1/4] Python 3.12 未安装，正在通过 winget 安装...
winget install Python.Python.3.12 --accept-source-agreements --accept-package-agreements
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" (
    set PYTHON=%LOCALAPPDATA%\Programs\Python\Python312\python.exe
) else (
    echo 错误：Python 3.12 安装失败
    pause
    exit /b 1
)

:found
echo Python 路径: %PYTHON%
echo.

:: 安装依赖
echo [2/4] 安装 markitdown 和 pyinstaller...
%PYTHON% -m pip install -q markitdown pyinstaller 2>&1
if errorlevel 1 (
    echo 错误：依赖安装失败
    pause
    exit /b 1
)

:: 创建包装脚本
echo.
echo [3/4] 创建包装脚本...
echo from markitdown import MarkItDown > "%TEMP%\markitdown_runner.py"
echo import sys >> "%TEMP%\markitdown_runner.py"
echo md = MarkItDown() >> "%TEMP%\markitdown_runner.py"
echo result = md.convert(sys.argv[1]) >> "%TEMP%\markitdown_runner.py"
echo print(result.text_content) >> "%TEMP%\markitdown_runner.py"

:: 打包为 exe
echo [4/4] 打包为 exe...
%PYTHON% -m PyInstaller --onefile --name markitdown --clean --noconsole --collect-all markitdown --collect-all magika --collect-all onnxruntime --collect-all mammoth --collect-all pdfplumber --collect-all pdfminer --collect-all python-pptx --collect-all openpyxl --collect-all beautifulsoup4 --collect-all speechrecognition --collect-all pydub "%TEMP%\markitdown_runner.py" 2>&1

:: 复制结果
if exist "dist\markitdown.exe" (
    copy /Y "dist\markitdown.exe" "tools\markitdown.exe" >nul
    echo.
    echo ============================================
    echo   构建完成！ tools\markitdown.exe
    echo ============================================
) else (
    echo.
    echo 错误：构建失败，dist\markitdown.exe 不存在
)

:: 清理
rmdir /s /q dist build markitdown.spec 2>nul
del "%TEMP%\markitdown_runner.py" 2>nul
echo 清理完成
pause
