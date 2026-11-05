void onLoad() {
    hook.after("io.github.easylsp.demo.Calculator", "add", new String[] { "int", "int" }, new HookCallback() {
        public void call(HookParam param) {
            plugin.log("after 原结果=" + param.getResult());
            param.setResult(Integer.valueOf(11));
        }
    });
}
void onUnload() { plugin.log("after 已卸载"); }
