#!/system/bin/sh

# 定义包名数组
PACKAGE_NAMES=("tv.danmaku.bili" "com.ss.android.ugc.aweme" "com.smile.gifmaker")

# 输出开始信息
echo "开始每隔 10 秒查杀以下包名的进程：${PACKAGE_NAMES[@]}"

# 无限循环，每隔 10 秒查杀一次进程
while true; do
    # 遍历包名数组
    for PACKAGE_NAME in "${PACKAGE_NAMES[@]}"; do
        # 查找包名对应的进程 ID
        PID=$(ps | grep "$PACKAGE_NAME" | grep -v "grep" | awk '{print $2}')

        # 如果找到进程，则杀死它
        if [ -n "$PID" ]; then
            kill -9 $PID
            echo "进程 $PACKAGE_NAME (PID: $PID) 已被终止"
        else
            echo " "
        fi
    done
    
    # 每隔 10 秒执行一次
    sleep 10
done