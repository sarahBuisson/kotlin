// IGNORE_BACKEND: JVM_IR
fun box(): String {
    230?.hashCode()

    return "OK"
}

// 1 INVOKESTATIC java/lang/Integer.valueOf