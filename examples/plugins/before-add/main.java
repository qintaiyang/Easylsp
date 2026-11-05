void onLoad() {
    hook.before("io.github.easylsp.demo.Calculator", "add", new String[] { "int", "int" }, new HookCallback() {
        public void call(HookParam param) {
            plugin.log("before add(" + param.args[0] + "," + param.args[1] + ")");
            param.args[0] = Integer.valueOf(4);
        }
    });
    plugin.log("before 已加载；add(2,3) 预期为 7");
}
void onUnload() { plugin.log("before 已卸载"); }
