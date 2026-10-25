package android.annotation

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SuppressLint(vararg val value: String)
