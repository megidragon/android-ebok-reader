-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn org.apache.commons.compress.**
# El código nativo de sherpa-onnx llama por JNI a invoke([F)Ljava/lang/Integer; en el objeto
# callback: R8 no debe renombrarlo, quitarlo ni fusionar la clase.
-keep class com.lectora.engine.tts.neural.SherpaOnnxBackend$SamplesCallback { *; }
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    public java.lang.Integer invoke(float[]);
}
