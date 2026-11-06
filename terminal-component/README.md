# Termux terminal component

Vendored terminal-emulator and terminal-view sources from termux/termux-app v0.118.3:
https://github.com/termux/termux-app/tree/v0.118.3

Only the terminal component, resources and JNI PTY implementation are included; no Termux app, services or shared library. Original headers are retained. See LICENSE.md for upstream licensing (GPLv3 with Apache-2.0 exceptions inherited from Android Terminal Emulator); Easylsp is GPLv3.

Build adaptation: Android namespace, SDK 36/minSdk 31, arm64, NDK 28.2.13676358 and Java 17. Functional source is unmodified unless noted below.

Android target 35 adaptation: TerminalSession uses public ParcelFileDescriptor adopt/dup APIs in place of reflection into FileDescriptor private fields. Reader/writer descriptors are separately owned; the PTY master is closed through ParcelFileDescriptor.

Cancellation adaptation: finishIfRunning signals the shell process group created by setsid, so ordinary child commands stop with the shell. App-side su sessions additionally record their root shell PID and stop that actual descendant tree through the already granted su manager.
