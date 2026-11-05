// Plain Frida JS; does not require the separately packaged Java bridge.
console.log('Attached to PID ' + Process.id);
Process.enumerateModules().slice(0, 10).forEach(m => console.log(m.name + ' ' + m.base));
const address = Module.findGlobalExportByName('getpid');
if (address !== null) {
  Interceptor.attach(address, { onLeave(result) { console.log('getpid returned ' + result.toInt32()); } });
}
