package io.github.easylsp.app.platform.analysis

import com.android.tools.smali.dexlib2.iface.reference.*

object DexSignatures {
    fun method(method: MethodReference): String = "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
    fun field(field: FieldReference): String = "${field.definingClass}->${field.name}:${field.type}"
    fun type(descriptor: String): String = when {
        descriptor.startsWith("[") -> type(descriptor.substring(1)) + "[]"
        descriptor.startsWith("L") -> descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        else -> mapOf("V" to "void", "Z" to "boolean", "B" to "byte", "C" to "char", "S" to "short", "I" to "int", "J" to "long", "F" to "float", "D" to "double").getValue(descriptor)
    }
    fun owner(name: String): String = if (name.startsWith("L") && name.endsWith(";")) name else "L${name.replace('.', '/')};"
    fun javaMethod(method: MethodReference): String = "${type(method.definingClass)}#${method.name}(${method.parameterTypes.joinToString(",") { type(it.toString()) }}):${type(method.returnType)}"
}
