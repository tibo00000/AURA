-if class com.aura.music.data.network.JobStatusResponseData
-keepnames class com.aura.music.data.network.JobStatusResponseData
-if class com.aura.music.data.network.JobStatusResponseData
-keep class com.aura.music.data.network.JobStatusResponseDataJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aura.music.data.network.JobStatusResponseData
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aura.music.data.network.JobStatusResponseData
-keepclassmembers class com.aura.music.data.network.JobStatusResponseData {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,float,com.aura.music.data.network.JobErrorPayload,java.util.List,java.lang.String,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
