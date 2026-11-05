void onLoad() {
    hook.replace("io.github.easylsp.demo.Calculator", "add", new String[] { "int", "int" }, new HookCallback() {
        public void call(HookParam param) {
            plugin.log("replace add(" + param.args[0] + "," + param.args[1] + ") => 42");
            param.setResult(Integer.valueOf(42));
        }
    });
}
void onUnload() { plugin.log("replace 已卸载"); }
