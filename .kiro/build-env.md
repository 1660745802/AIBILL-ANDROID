# bill-android 项目构建环境

## JAVA_HOME 问题

系统环境变量 JAVA_HOME 设置为 `C:\Users\likunhong\AppData\Local\Java\jdk-17.0.0.1`，但该路径**无效**（gradle 报 "JAVA_HOME is set to an invalid directory"）。

## 正确的构建方式

必须先覆盖 JAVA_HOME 为 Android Studio 自带的 JBR：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& C:\Users\likunhong\Documents\bill-android\gradlew.bat -p C:\Users\likunhong\Documents\bill-android <task> --no-daemon 2>&1
```

## 常用构建命令

```powershell
# 编译检查（快速，约 17s）
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; & C:\Users\likunhong\Documents\bill-android\gradlew.bat -p C:\Users\likunhong\Documents\bill-android compileReleaseKotlin --no-daemon 2>&1

# 完整 Release APK 构建
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; & C:\Users\likunhong\Documents\bill-android\gradlew.bat -p C:\Users\likunhong\Documents\bill-android assembleRelease --no-daemon 2>&1
```

## 注意事项

- 必须用 `--no-daemon`，因为系统 JAVA_HOME 无效会导致 daemon 启动失败
- PowerShell 中直接 `.\gradlew.bat` 可能有编码问题，用完整路径更稳
- 输出筛选用 `| Select-String -Pattern "error|Error|BUILD|FAIL|SUCCESS"` 来快速定位结果
- 项目路径：`C:\Users\likunhong\Documents\bill-android`
- APK 输出：`app\release\app-release.apk`
