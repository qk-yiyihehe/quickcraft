#!/bin/sh

#
# Copyright © 2015 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#

##############################################################################
#
#   此 POSIX 启动脚本由 Gradle 生成。
#
#   运行说明
#
#   （1）此脚本要求使用符合 POSIX 规范的 shell。如果 /bin/sh 不符合规范，
#       但系统装有 ksh、bash 等兼容的 shell，
#       请把该 shell 的名称放在整条命令前面，
#       如下所示。
#
#           ksh Gradle
#
#       BusyBox 等精简版 shell 无法运行此脚本，
#       因为它需要以下 POSIX shell 特性。
#         * 函数
#         * 展开语法 «$var»、«${var}»、«${var:-default}»、«${var+SET}»、
#             «${var#prefix}»、«${var%suffix}» 和 «$( cmd )»
#         * 可检查退出状态的复合命令，尤其是 «case»
#         * «command»、«set» 和 «ulimit» 等内置命令
#
#   修改说明
#
#   （2）此脚本适用于任何符合 POSIX 规范的 shell，因此避免使用
#       Bash 和 Ksh 等 shell 的扩展特性，尤其是数组。
#
#       把多个参数拼成一个空格分隔的字符串容易引起错误和安全问题。
#       此脚本尽量避免这种做法，
#       把选项逐个加入 "$@"，
#       最后交给 Java。
#
#       如果继承的环境变量 DEFAULT_JVM_OPTS、JAVA_OPTS 和 GRADLE_OPTS
#       需要按单词拆分，脚本会显式处理，
#       详见相应的行内注释。
#
#       脚本针对 AIX、Cygwin、Darwin、MinGW
#       和 NonStop 等操作系统做了专门适配。
#
#   （3）此脚本由下列 Groovy 模板生成。
#       https://github.com/gradle/gradle/blob/3d91ce3b8caaf77ad09f381f43615b715b53f72c/platforms/jvm/plugins-application/src/main/resources/org/gradle/api/internal/plugins/unixStartScript.txt
#       可在 Gradle 项目中找到该模板。
#
#       Gradle 项目地址 https://github.com/gradle/gradle/
#
##############################################################################

# 尝试设置 APP_HOME。

# 解析符号链接，$0 可能指向一个链接。
app_path=$0

# 逐级处理相互指向的符号链接。
while
    APP_HOME=${app_path%"${app_path##*/}"}  # 保留末尾的 /。没有目录部分时结果为空。
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
      /*)   app_path=$link ;; #(
      *)    app_path=$APP_HOME$link ;;
    esac
done

# 此变量通常不会用到。
# shellcheck disable=SC2034
APP_BASE_NAME=${0##*/}
# 如果设置了 $CDPATH，丢弃 cd 的标准输出（https://github.com/gradle/gradle/issues/25036）。
APP_HOME=$( cd -P "${APP_HOME:-./}" > /dev/null && printf '%s\n' "$PWD" ) || exit

# 将 QuickCraft 的 Gradle 发行包和依赖缓存保存在本仓库内。
if [ -n "${QUICKCRAFT_GRADLE_USER_HOME:-}" ] ; then
    GRADLE_USER_HOME=$QUICKCRAFT_GRADLE_USER_HOME
else
    GRADLE_USER_HOME=$APP_HOME/.gradle/user-home
fi
export GRADLE_USER_HOME

# 默认使用可用上限，也可以把 MAX_FD 设为 -1 以外的值来指定上限。
MAX_FD=maximum

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# 根据操作系统设置标志，值只能是 'true' 或 'false'。
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in                #(
  CYGWIN* )         cygwin=true  ;; #(
  Darwin* )         darwin=true  ;; #(
  MSYS* | MINGW* )  msys=true    ;; #(
  NONSTOP* )        nonstop=true ;;
esac



# 确定用于启动 JVM 的 Java 命令。
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM 在 AIX 上的 JDK 将可执行文件放在不同的路径下。
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
fi

# 在系统允许时提高文件描述符上限。
if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in #(
      max*)
        # POSIX 没有规定 sh 的 ulimit -H 选项，因此需要检查命令是否成功。
        # shellcheck disable=SC2039,SC3045
        MAX_FD=$( ulimit -H -n ) ||
            warn "Could not query maximum file descriptor limit"
    esac
    case $MAX_FD in  #(
      '' | soft) :;; #(
      *)
        # POSIX 没有规定 sh 的 ulimit -n 选项，因此需要检查命令是否成功。
        # shellcheck disable=SC2039,SC3045
        ulimit -n "$MAX_FD" ||
            warn "Could not set maximum file descriptor limit to $MAX_FD"
    esac
fi

# 按相反顺序收集 Java 命令的参数。
#   * 命令行参数
#   * 主类名称
#   * -classpath
#   * -D...appname 设置
#   * --module-path（按需传入）
#   * DEFAULT_JVM_OPTS、JAVA_OPTS 和 GRADLE_OPTS 环境变量

# 在 Cygwin 或 MSYS 下，运行 Java 前先把路径转换为 Windows 格式。
if "$cygwin" || "$msys" ; then
    APP_HOME=$( cygpath --path --mixed "$APP_HOME" )

    JAVACMD=$( cygpath --unix "$JAVACMD" )

    # 这里逐个转换参数，以兼容 /bin/sh。
    for arg do
        if
            case $arg in                                #(
              -*)   false ;;                            # 保留原有选项 #(
              /?*)  t=${arg#/} t=/${t%%/*}              # 符合 POSIX 路径形式
                    [ -e "$t" ] ;;                      #(
              *)    false ;;
            esac
        then
            arg=$( cygpath --path --ignore --mixed "$arg" )
        fi
        # 按参数总数循环移动列表中的项，
        # 使每个参数最后回到原有位置，
        # 同时保留可能发生的转换。
        #
        # for 循环会在开始前取得迭代列表，
        # 所以在此处修改位置参数既不会改变迭代次数，
        # 也不会改变每轮读到的 arg 值。
        shift                   # 移除旧参数
        set -- "$@" "$arg"      # 追加转换后的参数
    done
fi


# 在此添加默认 JVM 选项。也可通过 JAVA_OPTS 和 GRADLE_OPTS 向脚本传递选项。
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# 汇总传给 Java 命令的参数。
#   * DEFAULT_JVM_OPTS、JAVA_OPTS 和 optsEnvironmentVar 不允许包含 shell 代码片段，
#       其中带有 shell 特殊含义的字符都会被转义。
#   * 例如，${Hostname} 不会展开为环境变量的值，
#       命令行会收到字面值 '${Hostname}'。

set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
        "$@"

# 找不到 xargs 时停止执行。
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

# 用 xargs 解析带引号的参数。
#
# -n1 使 xargs 每行输出一个参数，并去掉引号和反斜杠。
#
# 在 Bash 中可以使用下面的写法。
#
#   readarray ARGS < <( xargs -n1 <<<"$var" ) &&
#   set -- "${ARGS[@]}" "$@"
#
# POSIX shell 不提供数组，也不支持 Bash 的进程替换语法。
# 因此逐个把参数作为一行输入交给 sed，
# 给 shell 元字符加上反斜杠，再用 eval 还原，
# 同时保留各参数之间的边界，
# 最后通过一条 set 语句设置位置参数。
#
# 如果任一变量含有换行符
# 或未配对的引号，这种处理会失败。
#

eval "set -- $(
        printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
        tr '\n' ' '
    )" '"$@"'

exec "$JAVACMD" "$@"
