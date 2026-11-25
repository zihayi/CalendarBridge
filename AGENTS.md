# CalendarBridge

通用 Android 日历同步助手，电脑端接入 `D:/code/WallNote`。
界面使用中文，WallNote 日程按北京时间传输。

## 来源和边界

- 基于原 `readonly-calendar` 工程的 v2 协议整理。
- 原工程、原接收服务、真实日历和手机上的安装均需保留。
- 不读取原工程的 `data/` 来理解数据结构；使用测试中的模拟数据。
- 不自动安装 APK，不添加或修改真实手机日程来验证。手机实际新增/修改由用户确认。

## 安全和写入语义

- 手机主动连接局域网 HTTPS，校验所配对电脑的固定 SHA-256 证书指纹。
- APK 不包含电脑地址、配对密钥或管理员密钥；安装后导入配对文件。
- `/desktop/*` 仅允许本机来源和独立 adminToken；手机接口使用单独的 token。
- Wi-Fi 发现只返回公开元数据，不因处于同一网络自动授权。首次配对需两端独立计算并核对验证码，由电脑本机批准；重连只信任已经配对的证书。
- 手机明确开启编辑且获得 READ_CALENDAR / WRITE_CALENDAR 后，才执行新增和修改。
- 修改验证原事件，事件与提醒使用一次 applyBatch；重复提交不得重复创建。
- uncertain 操作不能自动重放。收到手机成功回执和新快照后才显示“已同步”。
- 不支持删除手机日程。复杂重复和跨天日程在 WallNote 中只读展示。

## 私有文件

- `state/`、`data/`、`.local-build.json`、`dev/.build/`、`dist/` 不提交版本库。
- 不输出密钥、真实日程或生成的 BuildConfig 内容。
- `.local-build.json` 可以引用原工程签名目录；只使用已有签名，不覆盖或重新生成外部目录中的密钥。
- 沿用 `local.calendar.readonly` 包名，功能更新增加 versionCode。

## 验证

使用临时目录和模拟手机运行 `python -m unittest discover -s tests -v`。
构建命令为 `python dev/build_android.py`；首次自建签名需显式加 `--init-signing-key`。
编译通过不等同于真实手机同步成功。
