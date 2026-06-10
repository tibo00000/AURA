-if class com.aura.music.data.network.ApiError
-keepnames class com.aura.music.data.network.ApiError
-if class com.aura.music.data.network.ApiError
-keep class com.aura.music.data.network.ApiErrorJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.ApiError
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.ApiError
-keepclassmembers class com.aura.music.data.network.ApiError {
    public synthetic <init>(java.lang.String,java.lang.String,boolean,java.util.Map,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
