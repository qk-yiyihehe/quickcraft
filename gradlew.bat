@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  适用于 Windows 的 Gradle 启动脚本
@rem
@rem ##########################################################################

@rem 为变量设置局部作用域，并启用命令扩展。
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem 此变量通常不会用到。
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem 解析 APP_HOME 中的 "." 和 ".."，以缩短路径。
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem 将 QuickCraft 的 Gradle 发行包和依赖缓存保存在本仓库内。
if defined QUICKCRAFT_GRADLE_USER_HOME (
    set "GRADLE_USER_HOME=%QUICKCRAFT_GRADLE_USER_HOME%"
) else (
    set "GRADLE_USER_HOME=%APP_HOME%\.gradle\user-home"
)

@rem 在此添加默认 JVM 选项。也可通过 JAVA_OPTS 和 GRADLE_OPTS 向脚本传递选项。
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem 查找 java.exe。
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:execute
@rem 准备命令行参数。



@rem 执行 Gradle。
@rem endlocal 会在整行解析和变量展开之后生效，
@rem 因此可以在运行 Java 命令前退出局部作用域。
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dgradle.user.home=%GRADLE_USER_HOME%" "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem 通过 "%COMSPEC%" /c exit 退出，使脚本中的运算符能正常工作。
"%COMSPEC%" /c exit %ERRORLEVEL%
